# C1 — Database schemas

Contract document. Every statement here is normative: implementations copy it, they do not paraphrase
it. Traces to FR-01…FR-06, FR-15, FR-17, FR-18, FR-25 and INV-01…INV-05 of
[01](../01-requirements.md#br-fr); entities in [02](../02-domain-model.md#dm-entities).

## 1.1 Scope and the two-database rule {#ct1-scope}

Two physically separate PostgreSQL 16 instances, one database each:

| Database | Instance | Owner | Contains | Written by |
|---|---|---|---|---|
| `lockdb` | Cloud SQL `dlock-pg-lock` (REGIONAL) | lock-server | grants, sessions, revocation audit, `fencing_token_seq` | `lock-server` only, as user `lockapp` |
| `paydb` | Cloud SQL `dlock-pg-pay` (ZONAL) | payment-resource / rail-proxy | accounts, payouts, ledger, rail submissions, high-water marks | `payment-resource` and `rail-proxy`, as user `payapp` |

`payout-executor` holds **no** datasource. It reaches both stores over HTTP so that no client can
smuggle a write past a fence check.

**Why separate, and what one instance would destroy.** The project's claim is that fencing protects a
resource *the lock service cannot see*. Collapsing both onto one instance would silently invalidate it:

| If shared | What breaks |
|---|---|
| One transaction could span grant and money | The critical section becomes atomic, fencing becomes untestable decoration, and the demo proves nothing |
| One `now()` clock | Hides that expiry arithmetic and fence comparison are decided by two independent clocks |
| One failure domain | A lock-store failover would also stall the resource, so "stale holder returns and is rejected" can never be observed |
| One connection pool | Lock-acquire latency and payout latency stop being separable measurements (NFR-03) |

Fencing is enforced at two points, in two processes, neither of which is the lock service:
the `paydb` row ([1.6](#ct1-fenced)) and the rail-proxy high-water mark ([1.6](#ct1-fenced)).

## 1.2 `lockdb` DDL {#ct1-lockdb}

```sql
CREATE SEQUENCE fencing_token_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE lock_session (
    session_id   UUID        PRIMARY KEY,
    owner_id     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT lock_session_ttl_ck CHECK (expires_at > created_at)
);

CREATE TABLE lock_entry (
    lock_key      TEXT        PRIMARY KEY,
    owner_id      TEXT        NOT NULL,
    session_id    UUID        NOT NULL REFERENCES lock_session (session_id) ON DELETE CASCADE,
    fencing_token BIGINT      NOT NULL,
    acquired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT lock_entry_token_ck CHECK (fencing_token > 0),
    CONSTRAINT lock_entry_ttl_ck   CHECK (expires_at > acquired_at)
);

CREATE INDEX lock_entry_expiry_idx  ON lock_entry (expires_at);
CREATE INDEX lock_entry_session_idx ON lock_entry (session_id);

CREATE TABLE lock_revocation (
    id         BIGSERIAL   PRIMARY KEY,
    lock_key   TEXT        NOT NULL,
    prev_owner TEXT        NOT NULL,
    prev_token BIGINT      NOT NULL,
    operator   TEXT        NOT NULL,
    reason     TEXT        NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`lock_entry` holds **one row per held key, ever** — mutual exclusion (INV-06) is the primary key, not
application logic. `ON DELETE CASCADE` makes FR-04 (session death releases locks) a single `DELETE
FROM lock_session`. `lock_revocation` is append-only; nothing reads it on the hot path (FR-08, FR-30).

## 1.3 Acquire {#ct1-acquire}

One statement, one round trip, no `SELECT … FOR UPDATE`, no read-then-write window:

```sql
INSERT INTO lock_entry (lock_key, owner_id, session_id, fencing_token, acquired_at, expires_at)
VALUES (:key, :owner_id, :session_id, nextval('fencing_token_seq'),
        now(), now() + make_interval(secs => :ttl_seconds))
ON CONFLICT (lock_key) DO UPDATE
   SET owner_id      = EXCLUDED.owner_id,
       session_id    = EXCLUDED.session_id,
       fencing_token = nextval('fencing_token_seq'),
       acquired_at   = now(),
       expires_at    = now() + make_interval(secs => :ttl_seconds)
 WHERE lock_entry.expires_at <= now()
    OR (lock_entry.owner_id = EXCLUDED.owner_id AND lock_entry.session_id = EXCLUDED.session_id)
RETURNING fencing_token, owner_id, session_id, acquired_at, expires_at;
```

| Rows returned | Meaning | Acquire result |
|---|---|---|
| 1 | Granted: free key, genuinely expired lease taken over, or same session re-entering | `GRANTED` |
| 0 | Held by a live, different holder — the `DO UPDATE … WHERE` was false | `CONTENDED` |

The acquire result is `GRANTED`/`CONTENDED` and is **not** a `LockOutcome`: that name belongs to the
`forceRevoke` result record only ([C2 §2.3](C2-java-api.md#ct2-records)). Acquire returns
`Optional<LockHandle>` in Java and a `LockGrant` body over HTTP ([C3 §3.3](C3-http-surfaces.md#ct3-lock)).

**Warning 1 — `nextval` must be re-evaluated in the `DO UPDATE` branch.** Omitting
`fencing_token = nextval('fencing_token_seq')` from the `SET` list leaves the *previous* holder's
token on the row, so a takeover hands the new holder a token equal to the dead holder's. Every
downstream comparison is then `token < fence` → `false`… `token = fence`, which the strict `<`
rejects, or worse, a `<=` variant accepts. INV-04 dies. **This is the project's signature silent bug: the
lock still excludes, acquire still returns 200, and unit tests of acquire/release all pass** — only a
pause-and-return test with two workers exposes it. The negative control for it is mandatory (NFR-07).
Token *gaps* are expected and harmless: the `VALUES` `nextval` is consumed even when the insert
conflicts. The contract is strictly increasing, never gapless.

**Warning 2 — `now()` is transaction start time, from the server.** `now()`/`CURRENT_TIMESTAMP` is
fixed for the whole transaction and read from the *database* clock, never the client's. That is
exactly the property expiry arithmetic needs: all three comparisons in the statement
(`expires_at <= now()`, and both writes of `now() + ttl`) are evaluated against one instant on one
clock, so a lease can never be judged expired and renewed against different times, and client clock
skew cannot manufacture a grant. Use `clock_timestamp()` nowhere in this schema. **The same property
is why this backend cannot scale past one primary:** correctness rests on a single monotonic clock and
a single serialisation point. Add a second writable primary and expiry judgements diverge and
`fencing_token_seq` forks. FR-09's etcd backend exists because that ceiling is real, not because
PostgreSQL is slow.

## 1.4 Renew and release {#ct1-renew}

```sql
-- renew: FR-06. Fails if the lease already lapsed, even by 1 ms.
UPDATE lock_entry
   SET expires_at = now() + make_interval(secs => :ttl_seconds)
 WHERE lock_key      = :key
   AND fencing_token = :token
   AND expires_at    > now()
RETURNING fencing_token, expires_at;

-- release: FR-05. Compare-and-delete.
DELETE FROM lock_entry
 WHERE lock_key      = :key
   AND fencing_token = :token
RETURNING fencing_token;

-- session heartbeat: FR-04.
UPDATE lock_session
   SET expires_at = now() + make_interval(secs => :session_ttl_seconds)
 WHERE session_id = :session_id AND expires_at > now()
RETURNING expires_at;
```

| Statement | Zero rows means | Caller must |
|---|---|---|
| renew | Lease expired, or the row now belongs to a newer token | Raise `LockLostException`, abort the critical section, count `lock.session.lost` / log `lease_expired` |
| release | The grant is no longer ours | Return success and log `lock_released` with `stale=true` — do **not** retry, do **not** widen the predicate |
| heartbeat | Session already reaped | Raise `LockLostException`; a new session must be created, and every handle under the old one is void |

**The release predicate is exactly `lock_key` + `fencing_token`, and nothing else.** `DELETE … WHERE
lock_key = :key` alone lets a client that was already fenced out delete the *current* holder's grant —
a paused worker waking up and calling `close()` would hand the lock to a third party mid-payout.
Because the predicate includes the token, stealing another holder's lock is not expressible in the API
(FR-05). Adding `owner_id` or `session_id` buys **no** extra safety and is therefore excluded: the
token is drawn from a *single global sequence* ([1.7](#ct1-seq)) — in the etcd backend, the
cluster-wide revision — so one token value identifies one grant for all time, and the owner and session
that hold it are implied by the row. Widening the predicate only adds bind parameters the pinned Java
signatures cannot supply ([C2 §2.5](C2-java-api.md#ct2-spi)) and invites a caller to "fix" a failed
release by dropping the token instead. Renew (`UPDATE`) uses the same two columns. Renew's `expires_at > now()`
matters for the same reason: a lapsed row may already have been taken over, and resurrecting it would
produce two live holders at the same token.

## 1.5 `paydb` DDL {#ct1-paydb}

Money is **`BIGINT` minor units** everywhere. Never `FLOAT`/`DOUBLE`/`REAL`: binary floating point
cannot represent 0.10 exactly, so summing ledger rows would drift and INV-03 (`balance = Σ entries`)
would fail by cents that no reconciliation could explain.

```sql
CREATE TABLE account (
    account_id    UUID        PRIMARY KEY,
    currency      CHAR(3)     NOT NULL,
    balance_minor BIGINT      NOT NULL DEFAULT 0,
    fence         BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_currency_ck CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT account_balance_ck  CHECK (balance_minor >= 0),
    CONSTRAINT account_fence_ck    CHECK (fence >= 0)
);

CREATE TABLE payout (
    payout_id       UUID        PRIMARY KEY,
    account_id      UUID        NOT NULL REFERENCES account (account_id),
    amount_minor    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    state           TEXT        NOT NULL DEFAULT 'PENDING',
    claimed_by      TEXT,
    claim_token     BIGINT,
    idempotency_key TEXT        NOT NULL,
    attempt_count   INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payout_idem_uq     UNIQUE (idempotency_key),
    CONSTRAINT payout_amount_ck   CHECK (amount_minor > 0),
    CONSTRAINT payout_attempts_ck CHECK (attempt_count >= 0),
    CONSTRAINT payout_state_ck    CHECK (state IN ('PENDING','CLAIMED','RAIL_SUBMITTED',
                                   'RAIL_ACKED','RAIL_AMBIGUOUS','POSTED','FAILED','ABANDONED')),
    CONSTRAINT payout_pending_ck  CHECK (state <> 'PENDING'
                                   OR (claimed_by IS NULL AND claim_token IS NULL)),
    CONSTRAINT payout_claimed_ck  CHECK (state IN ('PENDING','FAILED')
                                   OR (claimed_by IS NOT NULL AND claim_token > 0))
);

CREATE INDEX payout_pending_idx ON payout (created_at) WHERE state = 'PENDING';
CREATE INDEX payout_account_idx ON payout (account_id);

CREATE TABLE ledger_entry (
    entry_id     UUID        PRIMARY KEY,
    payout_id    UUID        NOT NULL REFERENCES payout (payout_id),
    account_id   UUID        NOT NULL REFERENCES account (account_id),
    direction    TEXT        NOT NULL,
    amount_minor BIGINT      NOT NULL,
    fence        BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_direction_ck CHECK (direction IN ('DEBIT','CREDIT')),
    CONSTRAINT ledger_amount_ck    CHECK (amount_minor > 0),
    CONSTRAINT ledger_fence_ck     CHECK (fence > 0),
    CONSTRAINT ledger_leg_uq       UNIQUE (payout_id, account_id, direction)
);

CREATE TABLE rail_submission (
    submission_id   UUID        PRIMARY KEY,
    payout_id       UUID        NOT NULL REFERENCES payout (payout_id),
    idempotency_key TEXT        NOT NULL,
    presented_token BIGINT      NOT NULL,
    outcome         TEXT,
    rail_reference  TEXT,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT rail_outcome_ck  CHECK (outcome IS NULL
                                 OR outcome IN ('ACKED','REJECTED','TIMEOUT','FENCED')),
    CONSTRAINT rail_token_ck    CHECK (presented_token > 0),
    CONSTRAINT rail_resolved_ck CHECK ((outcome IS NULL) = (resolved_at IS NULL))
);

CREATE UNIQUE INDEX rail_submission_attempt_uidx ON rail_submission (payout_id)
    WHERE outcome IS DISTINCT FROM 'FENCED';

CREATE TABLE rail_high_water (
    account_id    UUID        PRIMARY KEY REFERENCES account (account_id),
    highest_token BIGINT      NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rail_hwm_token_ck CHECK (highest_token > 0)
);
```

| Constraint | Invariant it encodes | Failure it makes impossible |
|---|---|---|
| `ledger_leg_uq` | INV-03 | A retry double-posting the same leg and inflating the balance |
| `rail_submission_attempt_uidx` | **INV-02** | A second *forwardable* attempt for one payout (FR-18); fenced attempts are auditable, so they are excluded from the index |
| `rail_resolved_ck` | FR-20 | An "unknown" attempt quietly recorded as resolved |
| `account_balance_ck` | business rule | A negative balance created by a race the fence failed to stop |
| `payout_state_ck` | FR-22 | A state outside the machine reaching the database |
| `payout_claimed_ck` | FR-15 | A non-`PENDING` payout with no token to compare against |

`fence` on `ledger_entry` is deliberately redundant with `account.fence`: it records which grant
authored each leg, which is what makes reconciliation (FR-26) able to attribute a break to a holder.
`rail_high_water` lives in `paydb` but is written **only** by `rail-proxy`; `payment-resource` must
never touch it, or the two enforcement points stop being independent.

## 1.6 Fenced writes {#ct1-fenced}

All three run with `payment.fencing.enabled=true` / `rail.proxy.fencing.enabled=true`. The token
arrives in `X-Fencing-Token`; the account and ledger statements share one transaction.

```sql
-- (a) account: debit and advance the fence in one statement.
UPDATE account
   SET balance_minor = balance_minor - :amount_minor,
       fence         = :token,
       updated_at    = now()
 WHERE account_id    = :account_id
   AND currency      = :currency
   AND fence         < :token
   AND balance_minor >= :amount_minor
RETURNING balance_minor, fence;

-- (b) ledger legs: only postable once the fence is at or above our token (i.e. (a) succeeded).
INSERT INTO ledger_entry (entry_id, payout_id, account_id, direction, amount_minor, fence)
SELECT :entry_id, :payout_id, a.account_id, :direction, :amount_minor, :token
  FROM account a
 WHERE a.account_id = :account_id
   AND a.fence     <= :token
RETURNING entry_id;

-- (c) payout transition, same transaction, still fenced.
UPDATE payout
   SET state = 'POSTED', updated_at = now()
 WHERE payout_id = :payout_id AND state = 'RAIL_ACKED' AND claim_token <= :token
RETURNING state;

-- (d) rail-proxy high-water mark, its own transaction in its own process.
INSERT INTO rail_high_water (account_id, highest_token, updated_at)
VALUES (:account_id, :token, now())
ON CONFLICT (account_id) DO UPDATE
   SET highest_token = EXCLUDED.highest_token,
       updated_at    = now()
 WHERE rail_high_water.highest_token < EXCLUDED.highest_token
RETURNING highest_token;
```

| Stmt | 0 rows means | Caller must |
|---|---|---|
| (a) | Fenced out **or** insufficient funds — the predicate conflates them | Roll back, then run one diagnostic `SELECT fence, balance_minor` to classify. `fence >= :token` → throw `FencedOutException`, log `fenced_out` with key, presented and stored token, count `lock.fenced.out{resource=account}`. Otherwise a business rejection → `FAILED`. **Never retry with a fresh token** |
| (b) | The fence moved past `:token` between (a) and (b), i.e. we lost the lock | Roll back the whole transaction; `FencedOutException`, `lock.fenced.out{resource=ledger}`. A partial leg set must never commit (INV-03) |
| (c) | Not in `RAIL_ACKED`, or a newer holder already advanced it | Roll back; re-read state. If already `POSTED`, another holder finished the work — succeed idempotently, do not post again |
| (d) | A higher token has already submitted for this account | Do **not** forward to the rail. Insert `rail_submission` with `outcome='FENCED'`, log `duplicate_rail_submission_attempted`, count `rail.duplicate.attempted` and `lock.fenced.out{resource=rail}`, return 409 |

The `<` in (a)/(d) is strict; the `<=` in (b) is not, because (a) already stamped our own token. Using
`<=` in (a) would let the *same* token write twice after a partial failure; using `<` in (b) would
reject every legitimate posting. Both directions of that mistake are negative-control tests.

## 1.7 One global sequence, not a per-row version {#ct1-seq}

| Property | `fencing_token_seq` (global) | Per-row `version` column |
|---|---|---|
| Survives delete/recreate of the key | Yes — the counter does not live on the row | **No.** Release deletes the `lock_entry` row; the next acquire starts at 1 and reissues tokens a resource has already seen. INV-04 gone |
| Ordering across keys | Total, on one axis — any two grants in the system are comparable, so logs, traces and the rail high-water mark (which is per *account*, not per lock key) can be reasoned about together | Per-row only; two keys' counters are incomparable, and (d) above becomes unimplementable |
| Cost | One shared, non-transactional counter; gaps on rollback are acceptable | Cheaper, and worthless here |

Sequences are **non-transactional by design**: `nextval` is never rolled back, which is precisely why
tokens cannot repeat. The corollary is the restore hazard in INV-04 — restoring `lockdb` from a
backup rewinds the sequence, so restore procedure must fast-forward it above the highest
`rail_high_water.highest_token` and `account.fence` observed in `paydb` before any traffic resumes.

## 1.8 Migration file naming {#ct1-migrations}

Flyway, two independent histories, one per datasource. Never a shared location.

| Datasource | Location | Table |
|---|---|---|
| `spring.datasource.url` (`lockdb`) | `src/main/resources/db/migration/lockdb` | `flyway_schema_history` |
| `payments.datasource.url` (`paydb`) | `src/main/resources/db/migration/paydb` | `flyway_schema_history` |

`V<n>__<snake_case_description>.sql`, `n` a single increasing integer per location, description in the
imperative. Examples: `V1__create_lock_session_and_lock_entry.sql`,
`V2__create_lock_revocation.sql`, `V1__create_account_and_payout.sql`,
`V3__add_rail_submission_attempt_index.sql`. Repeatable seeds for the harness are
`R__seed_synthetic_accounts.sql` (synthetic data only, NFR-12). Applied migrations are immutable —
a mistake is corrected by `V<n+1>`, never by editing `V<n>`, whose checksum is recorded.
