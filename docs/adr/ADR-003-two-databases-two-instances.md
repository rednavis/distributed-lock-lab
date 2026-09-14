# ADR-003 — `lockdb` and `paydb` are separate databases on separate Cloud SQL instances {#adr3}

**Status:** Accepted, 2026-08-21
**Deciders:** project architect
**Requirements touched:** FR-09, NFR-02, NFR-08, INV-01, INV-06, M1, M3
**Contracts touched:** [C1 §1.1](../contracts/C1-database-schemas.md#ct1-scope), [C5 §5.6](../contracts/C5-config-build-and-naming.md#ct5-naming), [C5 §5.1](../contracts/C5-config-build-and-naming.md#ct5-config)

## A3.1 Context {#adr3-context}

PostgreSQL appears twice in this system in two entirely unrelated roles, and the fact that both roles
are played by the same product is a coincidence of implementation, not a design relationship.

| | `lockdb` | `paydb` |
|---|---|---|
| Role | **A lock backend** — one of two interchangeable implementations behind the same grant/renew/release contract (FR-09) | **The protected resource** — payouts, double-entry ledger rows, account balances and their `fence` column |
| Swappable? | Yes, by configuration: the whole point of the project is that etcd replaces it without the caller noticing | No. It *is* the state under protection |
| If it is unavailable | No new grants; holders' leases expire; work stalls | The critical section cannot complete; a rail submission may already have happened |
| Availability requirement | Whatever the lock-availability experiment demands | Whatever a study project tolerates |

The tempting cost saving is obvious and it is why this ADR exists: PostgreSQL happily hosts many
databases per instance, the project's data volumes are trivial (ASSUMPTION: under 1 GiB combined, under
50 concurrent connections), and one Cloud SQL instance would carry both with room to spare.

## A3.2 Decision {#adr3-decision}

**Two databases on two Cloud SQL instances in project `dlock-lab`, region `europe-central2`:
`dlock-pg-lock` hosting `lockdb`, provisioned REGIONAL (synchronous standby in a second zone), and
`dlock-pg-pay` hosting `paydb`, provisioned ZONAL.** Separate instances, separate credentials,
separate connection pools, separate `DataSource` beans ([C5 §5.1](../contracts/C5-config-build-and-naming.md#ct5-config)).
No cross-database query, no cross-database transaction, and therefore no temptation to make the lock
row and the ledger row share a commit.

**The binding reason is experimental attribution.** M1 (PostgreSQL backend) versus M3 (etcd backend)
is a failover comparison: kill the lock backend, measure how long grants are unavailable, and compare
the two implementations on the same axis. On one shared instance, the kill that removes the lock
service **also removes the protected resource at the same instant**. Every second of observed
downtime would then have two candidate causes, and no amount of post-hoc reasoning separates them —
the experiment does not merely get noisier, it stops measuring the thing it is named after. Worse,
the etcd arm (M3) would be unaffected by that same kill, so the two arms would not even be running
the same experiment. A comparison table produced that way is not publishable as a project deliverable.

**The failure-domain argument stands independently of the experiment.** A lock service is
infrastructure that the resource's own availability must not depend on, and vice versa. Collapsing
them creates a component whose loss is unrecoverable-by-construction: with the lock gone you cannot
safely proceed, and with the resource gone there is nothing to proceed *to*. Keeping them apart also
keeps the blast radius of a `lockdb` restore — which fast-forwards `fencing_token_seq`
([ADR-002 §A2.4](ADR-002-fencing-token-source.md#adr2-consequences)) — off the ledger entirely.

The asymmetry in tiers is deliberate and follows from the roles. The lock instance is REGIONAL
because its failover behaviour is the measured object; the pay instance is ZONAL because a study
project's ledger does not need a standby, and paying for one would spend money on the arm of the system
nobody is measuring.

## A3.3 Consequences {#adr3-consequences}

| | |
|---|---|
| **Positive** | M1-versus-M3 downtime is attributable to the lock backend by construction, not by argument. Independent failure domains: killing either side leaves the other diagnosable and the logs interpretable. Independent connection pools and resource limits, so lock-acquire storms cannot starve ledger writes (NFR-02). Two credential sets and two `DataSource` beans make an accidental single-transaction shortcut a compile-and-config problem rather than a silent correctness bug (INV-01). |
| **Negative** | **Roughly double the Cloud SQL line item** (ASSUMPTION: two smallest shared-core instances at ≈ €9–12/month each, plus the REGIONAL tier on `dlock-pg-lock` costing about 2× its own zonal price — ASSUMPTION: ≈ €30–40/month combined, versus ≈ €10–15 for one shared instance). Two instances to patch, two backup schedules, two sets of Terraform state to keep tidy, two IAM bindings, two private-IP attachments. Marginally more startup wiring in the app and one more way to misconfigure an environment. |
| **What we accept** | We are paying roughly €20–25/month (ASSUMPTION) for the ability to make one causal claim honestly. That is the correct trade for a portfolio project whose deliverable *is* the comparison table. Operationally the doubling is bounded by both instances being fully Terraform-managed and identically named per [C5 §5.6](../contracts/C5-config-build-and-naming.md#ct5-naming), so the second instance costs configuration lines, not judgement. |

## A3.4 Alternatives considered {#adr3-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| **One instance, two databases** (`lockdb` + `paydb` side by side) | Cheapest option that still keeps the schemas apart. Halves the bill, halves the ops surface, and PostgreSQL isolates databases well enough that no query can accidentally span them | Kills the M1-versus-M3 experiment: the instance-kill removes lock backend and protected resource simultaneously, so observed downtime is unattributable. Also blurs the failure domain — one shared WAL, one shared maintenance window, one shared restore |
| **One instance, one database, separate schemas** | Cheapest of all; simplest Terraform; one migration tool run | Strictly worse than the above. Now also **one connection pool and one set of resource limits**, so a lock-contention storm and ledger writes compete for the same slots, and a single `search_path` mistake puts a lock row and a ledger row in the same transaction — exactly the coupling fencing exists to survive without |
| **Postgres for the resource, etcd as the only lock backend** — drop M1 entirely | Removes the second instance *and* the second backend. One lock implementation to write, test and document; etcd is the more natural lock store anyway | Rejected on scope, not on engineering. **Both backends being first-class is what produces the comparison table**, and that table is a deliverable (FR-09, M1, M3). Dropping M1 saves the euros by deleting the reason the project exists |
| **Same instance, but restrict the kill to the `lockdb` process** (e.g. terminate its connections only) | Preserves attribution at some level without a second instance | Cloud SQL exposes no per-database failure primitive; the available fault injections act on the instance. A simulated fault the platform cannot actually produce is not evidence about failover |

## A3.5 Revisit when {#adr3-revisit}

| Trigger | Threshold (ASSUMPTION unless measured) | Then |
|---|---|---|
| The monthly bill becomes the binding constraint | Cloud SQL exceeds the project's standing budget (ASSUMPTION: ≈ €50/month all-in for the project) | Downgrade `dlock-pg-lock` to ZONAL **first** — that is the cheaper concession. Collapse to one instance only after the failover runs are recorded, and mark the comparison table as historical |
| The failover experiment has been run and recorded | M1 and M3 results committed with methodology and raw timings | `dlock-pg-lock` may be downgraded to ZONAL; the regional standby was bought for the measurement, not for the project's steady state. Keep two instances — the failure-domain argument (§A3.2) survives the experiment |
| A second protected resource appears | Any additional resource guarded by the same lock service | It joins `dlock-pg-pay` or gets its own instance, but never lands on `dlock-pg-lock`. The lock instance stays single-purpose |
| The project is ever pointed at anything real | Any non-synthetic payout, at all | `dlock-pg-pay` becomes REGIONAL too, and backup/PITR settings need an ADR of their own. Do not carry the ZONAL choice forward on the assumption it was a considered production decision — it was a project economy |
