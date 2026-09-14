# T-043 — Deterministic simulation test

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M4 (SDK and correctness proof) · **Estimate** 45 min — **over budget; split into two ledger rows, both inside T-043.** The boundary is exactly the checker rule of [07 §7.4](../docs/07-correctness-and-testing.md#test-dst) — **INV-01, INV-04, INV-05 and INV-06 are checked always; INV-02 and INV-03 only when the resource model is in play** — so: **T-043a** delivers the simulated world (clock, network, node model, seeded schedule, seed printing) plus the four always-on checkers **INV-01/04/05/06**, the last two over `EffectLog`. The **resource/payout model, INV-02 and INV-03, and schedule shrinking** are **T-043b**; write the seam for them in a, and leave a failing-by-default `@Disabled` placeholder naming T-043b. This scope does **not** move to T-044 — T-044 is the linearizability recorder and exporter only, and its spec carries none of this work.

**Preconditions**
- **T-017** — the PostgreSQL `LockStore` and the lock core exist behind the `LockStore`/`SessionRegistry` SPI, so the core can be driven without HTTP.
- **T-040, T-041** — the SDK's `Clock` and `Random` seams are injectable; the heartbeat and the full-jitter acquire loop wait only through those seams.
- **T-042** — the SIGSTOP experiment runs locally; its pause scenario is the wall-clock ancestor of the simulated `pause` fault.

**Goal** A single-threaded, seed-reproducible simulation of the lock core over a faulty network and skewed clocks that asserts, after every step, that **at most one client can successfully *mutate the resource*** ([07 §7.2](../docs/07-correctness-and-testing.md#test-invariant), verbatim) — never that at most one client *believes* it holds the lock, which is false on a correct system during a pause.

## 1. Why this task exists

Chaos testing finds bugs you cannot reproduce; a seed you can re-run byte-for-byte turns a heisenbug into a regression test. More importantly, this is where the project's central distinction is mechanised: mutual exclusion of *beliefs* (INV-06) is the lock service's own contract and will sometimes legitimately break under partition; mutual exclusion of *effects* (INV-01/02/05) must never break, because fencing enforces it outside the lock service. A test that only asserted "one holder believes it holds" would pass on a system that pays twice.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The DST specification: simulated faults, the knob list, the no-wall-clock rule, seed printing, per-step invariant checking, the seed set | `docs/07-correctness-and-testing.md#test-dst` |
| Which invariants are checked always vs only with the resource model | same section (INV-01/04/05/06 always; INV-02/03 with the resource model) |
| The belief-vs-effect distinction and which invariant belongs to whom | `docs/07-correctness-and-testing.md#test-invariant` |
| `LockStore` / `SessionRegistry` SPI — the only seam the simulation may drive | `docs/contracts/C2-java-api.md#ct2-spi` |
| Exception semantics the simulated client must honour (`LockLostException` terminal, `ContentionException` retryable) | `C2#ct2-exceptions` |
| Token monotonicity per key across restart and force-revoke (INV-04) | `docs/contracts/C1-database-schemas.md#ct1-seq` |
| Fenced-write semantics the effect checker models: `token ≤ fence` ⇒ zero rows | `C1#ct1-fenced` |
| Module placement and package (`harness`, `dev.lock.harness`) | `docs/contracts/C5-config-build-and-naming.md#ct5-modules`, `#ct5-layout` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report, quoting both.**

## 3. Deliverables

| Path | What |
|---|---|
| `harness/src/test/java/dev/lock/harness/sim/SimClock.java` | new: logical time in simulated millis; advances only when the scheduler advances it; separate monotonic and wall readings per node |
| `harness/src/test/java/dev/lock/harness/sim/SimNetwork.java` | new: message queue applying reorder, drop, duplicate, delay per link from the seeded `Random` |
| `harness/src/test/java/dev/lock/harness/sim/SimNode.java` | new: one client or server node — pause/resume, restart with and without state loss, per-node clock offset and drift |
| `harness/src/test/java/dev/lock/harness/sim/Schedule.java` | new: the seeded fault schedule — knobs, generation from a seed, and a printable/parseable trace form |
| `harness/src/test/java/dev/lock/harness/sim/InvariantChecker.java` | new: the always-on set INV-01/04/05/06 in a (INV-01 and INV-05 over `EffectLog`); the resource-model hook for INV-02/INV-03 declared in a, implemented in b |
| `harness/src/test/java/dev/lock/harness/sim/EffectLog.java` | new: the append-only record of *attempted* and *accepted* mutations `(key, token, step, node)` — the thing the effect invariants are checked over |
| `harness/src/test/java/dev/lock/harness/SimulationTest.java` | new: the JUnit entry point — fixed push seed set, `-Ddlock.sim.seeds=` override, regression seeds |
| `harness/src/test/resources/sim/regression-seeds.txt` | new: one seed per line, empty at first; every historical failure is appended forever |
| `harness/README.md` | modify: how to re-run a seed and how to add a failing one |

## 4. Specification

**Time.** No `Thread.sleep`, no `System.nanoTime`, no `Instant.now` anywhere in the simulation or in the code it drives. A step loop pops the earliest scheduled event, sets logical now to its timestamp, delivers it, then runs the checker. Each node reads its own monotonic and wall clocks, skewed **independently** by a per-node offset and drift rate — skewing them together hides exactly the bugs this finds.

**Faults, all seed-driven.** Reorder (probability, max displacement), drop (probability per link), duplicate (probability, count), delay (distribution per link, with a tail-heavy option), process pause (victim, start step, duration — the SIGSTOP analogue), per-node clock skew (offset and drift), node restart (with and without state loss). All knobs come from the `Schedule` derived from one `long` seed; nothing reads a global random source.

**The assertion that matters.** The checker distinguishes three sets per key: nodes that *believe* they hold, mutations *attempted*, and mutations *accepted*. INV-06 asserts at most one grant is live from the service's own view and is allowed to be tested separately. The load-bearing assertion is over `EffectLog`: for every key, the accepted mutations are totally ordered by strictly increasing token, and any attempt whose token is not greater than the highest accepted token for that key is **rejected** (models the fenced `UPDATE` affecting zero rows). Two nodes believing they hold simultaneously is *not* a failure; two nodes both getting an accepted mutation is. State the difference in the test class doc in one sentence each — reviewers read this file.

**INV-04.** Tokens per key strictly increase across restart, restart-with-state-loss, and force-revoke. A restart that resets a counter is the classic bug; the schedule must generate it.

**Reproducibility and reporting.** Every run prints its seed as the **first line** of output and again in any failure message. A failure prints the step trace in the `Schedule` parseable form, the last N steps before the violation, and the offending `(key, token, node)` tuples. Seed set: a small fixed set on every push (ASSUMPTION 16 seeds, budget under 60 s total), 1…N nightly (ASSUMPTION 10 000), plus every seed in `regression-seeds.txt` always.

**Structure.** Single-threaded and deterministic: no `ExecutorService`, no concurrent collections, no iteration over `HashMap` where order affects behaviour (use ordered maps), no identity hash codes in any decision.

## 5. Acceptance criteria

1. `./gradlew :harness:test --tests '*SimulationTest*'` passes on the fixed push seed set in under 60 s and prints the seed set on the first line.
2. Running one seed twice produces byte-identical output including the step trace (diff of two captured runs is empty).
3. `grep` finds no `Thread.sleep`, `System.nanoTime`, `System.currentTimeMillis`, `Instant.now`, or `ExecutorService` under `harness/src/test/java/dev/lock/harness/sim`.
4. A deliberately broken checker experiment proves the test bites: with the fence check disabled by a test-only flag, at least one of the fixed seeds fails with two accepted mutations for one key — record which seed in `harness/README.md`.
5. A schedule containing restart-with-state-loss shows tokens still strictly increasing (INV-04) or fails naming the two equal tokens.
6. A scenario where a paused node resumes and attempts a stale-token mutation shows `attempted` recorded and `accepted` **not** recorded — the simulated analogue of T-042 Run 2.
7. A test asserts explicitly that two simultaneous *beliefs* do not fail the run, with a comment naming INV-06 vs INV-01/05 — the belief/effect distinction is executable, not just prose.
8. `regression-seeds.txt` exists, is read by the test, and an added bogus-format line fails fast with a clear message.
9. After T-043a the hook (resource model, INV-02/03, shrinking) exists as a named, `@Disabled` placeholder referencing **T-043b**; after T-043b it is enabled and green, and no `@Disabled` placeholder remains under `sim`.

## 6. Verification

```
./gradlew :harness:test --tests '*SimulationTest*' | tee /tmp/sim-a.txt
./gradlew :harness:test --tests '*SimulationTest*' -Ddlock.sim.seeds=424242 | tee /tmp/sim-b1.txt
./gradlew :harness:test --tests '*SimulationTest*' -Ddlock.sim.seeds=424242 | tee /tmp/sim-b2.txt
diff <(grep -v 'BUILD\|Total time\|Task :' /tmp/sim-b1.txt) <(grep -v 'BUILD\|Total time\|Task :' /tmp/sim-b2.txt)
grep -rn "Thread.sleep\|nanoTime\|currentTimeMillis\|Instant.now\|ExecutorService" harness/src/test/java/dev/lock/harness/sim
./gradlew :harness:test --tests '*SimulationTest*' -Ddlock.sim.disableFence=true   # expect: FAILURE, two accepted mutations
```

Expected observable result: the `diff` is empty; the fence-disabled run fails and names a seed; the grep returns nothing.

## 7. Out of scope

Nothing in this spec is handed to T-044. The resource/payout model, INV-02/INV-03, and schedule shrinking are deferred within this task, to **T-043b**. Linearizability checking against a reference model — a later M4 task. Any etcd-specific simulation of `ModRevision` beyond what the SPI exposes (M3 is done; do not fork behaviour per backend here). Real containers, real sockets, real time — that is T-042 and M6. Nightly seed sweeps in CI — **M7**.

## 8. Hazards

- **Asserting on beliefs.** The tempting invariant ("at most one node thinks it holds") passes on a system that pays twice; §7.2 separates INV-06 from INV-01/02/05 for exactly this reason.
- Skewing monotonic and wall clocks by the same offset silently removes the class of bug where code mixes the two.
- A single `HashMap` iteration or a hash-order-dependent tie-break destroys reproducibility, and it will not show up until a JVM upgrade.
- Printing the seed only on failure is not enough — a run that passes today and fails tomorrow needs yesterday's seed.
- Driving the core through HTTP instead of the SPI reintroduces real time and real threads; go through `LockStore`/`SessionRegistry` only.
- Do not run git; regression seeds are appended to the file, nothing is committed (ADR-011).

## 9. On completion

Mark the T-043a (then T-043b) row done in `tasks/README.md`, record the fixed push seed set, the seed that catches the disabled fence, and — when closing T-043a — the exact scope left to **T-043b** (resource model, INV-02/03, shrinking) so the next session does not re-derive it.
