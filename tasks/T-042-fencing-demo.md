# T-042 — THE fencing experiment: SIGSTOP

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M4 (SDK and correctness proof) · **Estimate** 30 min if the compose stack is healthy; if the stack needs debugging, stop at a working Run 1 and split the Run 2 automation into T-042b — say so in the ledger rather than half-doing both.

**Preconditions**
- **T-005** — `deploy/compose/compose.yaml` and the single shared `deploy/images/Dockerfile` exist, so the five Java services can be built and run locally under the `apps` profile. There is no other compose file and no per-service Dockerfile anywhere in the repo.
- **T-027** — M2 complete: both fence points (the `paydb` conditional `UPDATE` and the rail-proxy persisted high-water mark), both kill switches (T-026), and the `lock.fenced.out` / `rail.duplicate.attempted` counters on `/actuator/prometheus` are implemented in the services.
- **T-040, T-041** — the SDK holds a lease conservatively and acquires with a bounded jittered loop; `payout-executor` can be pointed at it or left on its direct calls, whichever the stack already does.
- **Running before the script starts**: the seven containers `lockdb`, `paydb`, `etcd`, `lock-server`, `payment-resource`, `rail-proxy`, `rail-stub`, built from the shared Dockerfile and named explicitly on the `up` line (§6), all reporting `healthy`. `payout-executor` is declared in the `apps` profile but is **not** started by compose here — the script launches the two executor processes (A and B) itself, because it must own their PIDs to pause one. Verify the seven are healthy before running anything; a stack that is not fully up is the most common cause of an `INCONCLUSIVE` verdict below.

**Goal** Produce a scripted, repeatable local demonstration that a stopped-then-resumed worker causes a duplicate payment and a corrupted ledger with fencing off, and is rejected at both fence points with fencing on.

## 1. Why this task exists

Everything before this is plumbing that reviewers must take on trust. This is the first artifact that *proves* the thesis of the project: the lock service behaves perfectly in both runs, and the system is still corrupted in Run 1 — every component correct, the composition not. Run 2 is worthless unless Run 1 fails, so the script must assert the failure as loudly as the fix.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The experiment's steps, both runs' expected output, and the four "pass for the wrong reason" traps | `docs/07-correctness-and-testing.md#test-fencing` |
| The two kill switches `payment.fencing.enabled`, `rail.proxy.fencing.enabled` — names, defaults, and that they exist only for this run | `docs/contracts/C5-config-build-and-naming.md#ct5-killswitches` |
| Fence point (a): `UPDATE … WHERE fence < :token`, zero rows ⇒ 409 | `docs/contracts/C1-database-schemas.md#ct1-fenced` |
| Fence point (c): rail-proxy persisted highest-token-per-account | `docs/contracts/C3-http-surfaces.md#ct3-railproxy` |
| Error codes `FENCED_OUT`, `DUPLICATE_SUBMISSION`, `PAYOUT_NOT_CLAIMABLE` and their retry verdicts | `C3#ct3-errors` |
| Executor HTTP hops P2 claim / P3 post and the required `X-Fencing-Token`, `X-Owner-Id`, `X-Idempotency-Key` | `C3#ct3-pay`, `C3#ct3-conventions` |
| Log events `lock_granted`, `fenced_out`, `rail_ambiguous` and their required fields | `docs/contracts/C4-observability.md#ct4-logs` |
| The must-be-zero counters `rail.duplicate.attempted` and `lock.fenced.out` | `C4#ct4-zero` |
| Config keys used to shorten the lease and the executor work duration | `C5#ct5-config` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report, quoting both. In particular, do not invent a switch name.**

## 3. Deliverables

| Path | What |
|---|---|
| `harness/fencing-demo.sh` | new, executable: the whole experiment — `--fencing on\|off`, `--repeat N`, `--seed`, `--keep` flags; exit code 0 only when the run's *expected* verdict is observed |
| `harness/fencing-demo/expected-off.md` | new: the Run 1 verdict table (2 rail requests, 2 ledger pairs, balance ≠ sum, stale token applied) |
| `harness/fencing-demo/expected-on.md` | new: the Run 2 verdict table (1 rail request, 1 ledger pair, `FENCED_OUT` at both points, both counters incremented) |
| `harness/fencing-demo/README.md` | new: how to run, what to look at, the "pass for the wrong reason" checklist copied as a checklist |
| `harness/src/test/java/dev/lock/harness/FencingDemoIT.java` | new: JUnit wrapper invoking the script for both runs so CI can gate on it (tagged so it is opt-in, not on every push) |
| `docs/07-correctness-and-testing.md` | modify **only** §7.3's status line to record that it is now runnable, plus the captured artifact paths |

## 4. Specification

**Fixture.** Script-created, never hand-seeded: one `account` with a known synthetic balance, one `payout` in `PENDING` for a fictional mid-size PSP (amount 100 minor units, ASSUMPTION). Lease TTL 5 s, executor work duration 20 s, rail-stub delay well under the TTL — all passed as environment/config, never edited into a source file. Each repetition uses fresh ids so repetitions do not alias.

**Sequence.** Start executor **A** on the payout; take A's granted token `tA` from the `LockGrant` response body its SDK received, logged by the executor at startup — the `lock_granted` event is only schema-enforced from T-062, so do not depend on its field set here, and if lock-server does not yet emit it, read the token from the executor's own line and note that in the README; `kill -STOP` A's PID roughly 1 s in; wait past TTL plus server grace; start executor **B** on the same payout and wait for it to finish, capturing `tB`; `kill -CONT` A; wait for A to terminate. Then read the verdict: account balance, `ledger_entry` rows with their `fence` values, `rail_submission` rows, the rail stub's received-request log, and the two counters from `/actuator/prometheus`.

**Assertions per run.** Run 1 (`--fencing off`, both switches false) **must fail the invariants**: exactly 2 rail requests for the payout's client reference, two ledger pairs, balance ≠ sum of entries, and evidence that the later mutation carried the *stale* `tA`. A Run 1 that shows one request is a **script bug or a mistimed pause** and must exit non-zero with that diagnosis, not be reported as good news. Run 2 (defaults, both switches true) must show `tB > tA`, exactly one rail request, one ledger pair, balance = sum, a `FENCED_OUT` (or `DUPLICATE_SUBMISSION`) rejection from the rail-proxy *and* a zero-rows-affected 409 from `payment-resource`, and both counters non-zero. Additionally run the two **single-switch** variants so each fence point is proven to reject on its own — a point that never saw the request is not a passing point.

**Discrimination.** The script must distinguish a fence rejection from a payout-FSM rejection: assert on the error **code** plus the `lock.fenced.out` delta, never on "the second attempt failed". If A aborts on its own liveness check before reaching the rail, classify it as `INCONCLUSIVE` and retry with an adjusted pause offset up to a small bound.

**Capture.** Every run writes a timestamped directory under `harness/fencing-demo/out/` containing **six** artifact kinds: executor A's log, executor B's log, the `fenced_out` / `rail_ambiguous` events grepped from the service logs, the rail stub request log, before/after balance and `ledger_entry` rows, and the two counter snapshots from `/actuator/prometheus`. This directory is the portfolio artifact; the README says so. **No trace evidence is captured here** — distributed tracing and the `lock.token` span attribute do not exist until **T-067** (M6); the trace showing one token's life across four services is captured there and in the T-069 game day, and the README must point forward to them rather than leave an empty `traces/`.

## 5. Acceptance criteria

1. `harness/fencing-demo.sh --fencing off` exits 0 and its capture directory shows exactly 2 rail requests, 2 ledger pairs, and balance ≠ sum.
2. `harness/fencing-demo.sh --fencing on` exits 0 and shows exactly 1 rail request, 1 ledger pair, balance = sum.
3. Run 2 output contains a rail-proxy rejection with code `FENCED_OUT` or `DUPLICATE_SUBMISSION` **and** a `payment-resource` 409 `FENCED_OUT`, with `presented` and `stored`/`highest` tokens printed.
4. Both single-switch variants exit 0, each showing rejection at exactly the point left enabled.
5. `--repeat 20` produces 20 identical verdicts per mode; any `INCONCLUSIVE` repetition is reported in the summary line and does not silently count as a pass.
6. The captured directory for each run contains all six artifact kinds listed in §4 Capture — and no trace artifact, which is T-067's.
7. `FencingDemoIT` passes locally when the stack is up and is excluded from the default push pipeline by tag.
8. `grep -c` shows the script contains no hard-coded payout id, account id or token.

## 6. Verification

```
docker compose -f deploy/compose/compose.yaml --profile apps build          # shared deploy/images/Dockerfile
docker compose -f deploy/compose/compose.yaml up -d \
  lockdb paydb etcd lock-server payment-resource rail-proxy rail-stub       # payout-executor deliberately omitted
docker compose -f deploy/compose/compose.yaml ps                            # 7 services, all State=healthy
harness/fencing-demo.sh --fencing off  ; echo "exit=$?"
harness/fencing-demo.sh --fencing on   ; echo "exit=$?"
harness/fencing-demo.sh --fencing on --repeat 20 | tail -5
psql "$PAYDB_URL" -c "select id, fence from ledger_entry order by id"
psql "$PAYDB_URL" -c "select account_id, highest_token from rail_high_water"
curl -s localhost:8081/actuator/prometheus | grep -E 'lock_fenced_out|rail_duplicate_attempted'
ls harness/fencing-demo/out/
```

Expected observable result: two capture directories whose verdicts match `expected-off.md` and `expected-on.md` line for line; the counters read non-zero after Run 2 and the ledger shows exactly one balanced pair.

## 7. Out of scope

The deterministic simulation and its seeded schedule — **T-043**. Linearizability checking — a later M4 task. Running any of this on GKE, or the Kubernetes-level pod-pause version — **M5/M6**. Benchmark numbers and the published write-up — **M7 (T-070…075)**. Changing fence-point implementations: if a fence point does not reject, that is an M2 defect — report it, do not patch it here.

## 8. Hazards

- **A Run 1 that passes is the failure mode**, not a relief. §7.3's trap table is normative: assert the duplicate.
- `kill -STOP` on the container's PID 1 pauses the wrong thing; target the executor JVM's own PID (or use `docker pause` on a single-process executor container) and record which mechanism you used.
- Pause too early and A has not yet submitted anything; too late and it has already finished. The offset is a tuned ASSUMPTION — write the value you used into the README.
- Turning a kill switch off "to make the test pass" is the abuse C5 §3.x forbids; the switches exist to demonstrate corruption inside this harness only.
- Do not run git. Nothing in this task commits anything (ADR-011).

## 9. On completion

Mark T-042 done in `tasks/README.md` and add one line naming the capture directory that is the reference artifact, the pause offset used, and the pause mechanism (`kill -STOP` vs `docker pause`). Record any `INCONCLUSIVE` rate seen at `--repeat 20`.
