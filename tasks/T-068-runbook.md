# T-068 — The runbook, one entry per alert

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 — Observability and SRE · **Estimate** 30 min (tight; write in the priority order of §4.1 and if the clock runs out stop after entry 10, leaving rows 11–12 open in `tasks/README.md` — do not thin the entries to fit)

**Preconditions** — T-060…T-067 (the M6 telemetry chain: metrics, structured logs and spans emitted per C4, the `/actuator/prometheus` scrape path verified, dashboards, SLO definitions and the Cloud Monitoring alert policies in Terraform). You inherit alert policies that fire and a `documentation` field on each that points at design prose in `docs/08-operations.md` — there is no operator-facing runbook file yet.

**Goal** — Produce `docs/runbook.md`: one anchored, self-contained entry per alert policy, reconciled one-for-one with the alert catalogue in both directions, and repoint every reference at it.

## 1. Why this task exists

`06 §6.7` deletes any page that fails test (3) — *names a runbook section*. That test was unmeetable until the catalogues were reconciled: `06 §6.7` linked to six anchors `08 §8.2` did not define, and `08 §8.2` defined entries no alert linked to. Both sides are now twelve, one-for-one — except `#rb-etcd-size`, which still has no alert row (`06 §6.7` is deliberately silent on db-size growth; record that as a finding, do not invent a policy for it here). A Cloud Monitoring `documentation` link discovered broken at 03:00 is exactly the failure `08 §8.2` warns about. This task creates the single operator artifact and closes the loop mechanically.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Metric names quoted in diagnostics (`lock_fenced_out_total`, `rail_duplicate_attempted_total`, `lock_lease_expired_total`, `payout_backlog_age_seconds`, `lock_acquire_seconds_count`) | `docs/contracts/C4-observability.md#ct4-metrics` |
| The two counters whose healthy value is exactly zero | `docs/contracts/C4-observability.md#ct4-zero` |
| Log event names and fields quoted in "first three" (`fenced_out`, `rail_ambiguous`, `lock_granted`) | `docs/contracts/C4-observability.md#ct4-logs` |
| Scrape contract and the `http-metrics` named-port trap (the `metrics-target-down` entry) | `docs/contracts/C4-observability.md#ct4-scrape` |
| Kill-switch keys named in the DO NOT clauses | `docs/contracts/C5-config-build-and-naming.md#ct5-killswitches` |
| Config keys quoted in causes (`lock.default.ttl`, `lock.client.safety-margin`) | `docs/contracts/C5-config-build-and-naming.md#ct5-config` |
| Fence points referenced by the fenced-out and token-regression entries | `C1#ct1-fenced`, `C3#ct3-railproxy` |
| GCP/K8s names in commands (`dlock`, `dlock-pg-lock`, `dlock-etcd`, `europe-central2`) | `docs/contracts/C5-config-build-and-naming.md#ct5-naming` |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop, report both spellings, implement neither. A metric spelled from memory into a runbook is a diagnostic that returns nothing at 03:00.

## 3. Deliverables

| Path | What |
|---|---|
| `docs/runbook.md` | **New.** The runbook of record: preamble, anchor index, 12 entries, reconciliation appendix |
| `docs/06-observability-and-slo.md` | **Modify** the Runbook column of `§6.7` only: every link becomes `runbook.md#<anchor>` |
| `docs/08-operations.md` | **Modify**: `§8.2` preamble gains one paragraph naming `docs/runbook.md` as the executable copy and `§8.2` as the design rationale behind its shape. Delete no entry content |
| the alert-policy definitions under `deploy/terraform/` | **Modify** each policy's `documentation` field to deep-link its runbook anchor. Locate them with `grep -rln alert deploy/terraform`; if T-066 has not produced them, record that as a deviation and do not invent a tree |

## 4. Specification

### 4.1 The entry set and its order of writing

Twelve entries. Write in this order so a truncated session still ships the entries that matter: **correctness first** (1, 2, 9), **then symptoms** (3, 4, 5, 10), **then causes** (6, 7, 8, 11, 12).

| # | Alert-policy name | Anchor | Sev | Notes |
|---|---|---|---|---|
| 1 | `fenced-out` | `#rb-fenced-out` | S1 page | carries the DO-NOT-disable warning (§4.3) |
| 2 | `rail-duplicate-attempted` | `#rb-rail-duplicate` | S1 page | `06 §6.7`'s **Duplicate rail submission** row already carries this anchor name — only the file part of the link changes |
| 3 | `payout-success-slo-fast-burn` | `#rb-payout-burn` | S1 page | |
| 4 | `lock-acquire-unavailable-burn` | `#rb-acquire-burn` | S1 page / S2 page-in-hours | one entry serves **both** burn policies (fast and slow); `06 §6.7`'s **Fast budget burn** and **Slow budget burn** rows both already carry this anchor name |
| 5 | `payout-backlog-aging` | `#rb-backlog` | S2 page | |
| 6 | `lease-expiry-elevated` | `#rb-lease-expiry` | S3 ticket | leading indicator; says so in the first line |
| 7 | `etcd-quorum-degraded` / `etcd-elections-over-budget` | `#rb-etcd-quorum` | page / ticket | absorbs `06 §6.7`'s **etcd leader changes** row, which already carries this anchor name |
| 8 | `etcd-db-size-high` | `#rb-etcd-size` | S3 ticket | compact **then** defragment, one member at a time; the read-only trap |
| 9 | `token-regression-detected` | `#rb-token-regression` | highest | `06 §6.7`'s **Token regression** row links here |
| 10 | `metrics-target-down` | `#rb-target-down` | S2 page | `06 §6.7`'s **Metrics absent** row already carries this anchor name |
| 11 | `etcd-wal-fsync-p99-high` | `#rb-etcd-disk` | S3 ticket | source prose is `08 §8.2.11` |
| 12 | `pg-connection-saturation` | `#rb-pg-connections` | S3 ticket | source prose is `08 §8.2.12`; `dlock-pg-lock` only |

### 4.2 Fixed shape, every entry, in this order

Six labelled parts, per `08 §8.2`: **Means / impact** (one sentence of user-visible consequence, and whether an invariant is at risk) · **First three** (three numbered, copy-pasteable diagnostics — a `kubectl -n dlock` command, a PromQL expression, a piece of evidence to pull) · **Causes, by real frequency** (ordered, not alphabetical, with the observed discriminator for each) · **Remediate / rollback** (including the rollback command and the confirmation window) · **Escalate** (the precise condition that promotes severity) · **DO NOT** (the specific wrong action a tired operator will reach for).

Rules: the first diagnostic is never the word *investigate* (`08 §8.4` scenario 10 is a test of exactly that); every metric or log field is a contract identifier; each entry ends with a `Last executed:` line whose value is `never — see T-069`.

### 4.3 The fenced-out entry

It must state, in its own words and unhedged, that the fencing check is **not** to be disabled to silence the alert — naming both `payment.fencing.enabled` and `rail.proxy.fencing.enabled` — and give the reason in consequence terms: the alert stops, stale writes start landing, and the next symptom is a reconciliation break found days later instead of a counter found in seconds. Also forbidden and named: hand-advancing the stored fence to let the write through, and re-submitting to the rail by hand. It must also say the healthy value is zero, so "it only fired once" is not a reason to lower severity.

### 4.4 Preamble, index, appendix

Preamble: assumed shell state (`kubectl -n dlock`, `etcdctl` endpoints exported, `gcloud` project `dlock-lab`), the page-vs-ticket rule in one line, and the standing instruction that a wrong runbook is a finding to be fixed, not worked around. Then a table of all twelve anchors with severity and one-line meaning. Appendix: a **reconciliation table** — every `06 §6.7` alert row → its runbook anchor, plus a column recording that all twelve anchor **names** already match one-for-one (only the file part of each link changes, `08-operations.md#…` → `runbook.md#…`) and flagging entry 8 `#rb-etcd-size`, the one entry with no alert row in `06 §6.7` yet.

## 5. Acceptance criteria

1. `docs/runbook.md` exists and defines exactly the twelve anchors in §4.1, each on a heading.
2. Each of the twelve entries contains all six part labels of §4.2, in that order.
3. Every `runbook.md#…` link in `docs/06-observability-and-slo.md` resolves to an anchor defined in `docs/runbook.md`; no `08-operations.md#rb-` link remains in `§6.7`.
4. Every anchor in `docs/runbook.md` is referenced by at least one alert row in `06 §6.7` (both directions closed).
5. The `#rb-fenced-out` entry contains both kill-switch key names and an explicit prohibition on setting either false.
6. No entry's first diagnostic is a bare "investigate"; `grep -in 'investigate'` yields no hit inside a **First three** block.
7. Each alert policy under `deploy/terraform/` carries a `documentation` value containing `runbook.md#rb-`, or the deviation of §3 is recorded in `tasks/README.md`.
8. Every metric, log-event and config identifier in the file appears verbatim in C4 or C5.
9. Each entry ends with `Last executed: never — see T-069`.

## 6. Verification

```bash
cd /Users/aarashke/Projects/Profile/materials/system-design/distributed-lock-lab
grep -o '{#rb-[a-z-]*}' docs/runbook.md | sort -u                  # expect 12 anchors
grep -o 'runbook.md#rb-[a-z-]*' docs/06-observability-and-slo.md | sed 's/.*#//' | sort -u > /tmp/linked
grep -o '{#rb-[a-z-]*}' docs/runbook.md | tr -d '{}#' | sed 's/^rb-/rb-/' | sort -u > /tmp/defined
comm -3 /tmp/linked /tmp/defined                                    # expect NO output
grep -c 'Last executed: never' docs/runbook.md                      # expect 12
grep -n 'payment.fencing.enabled\|rail.proxy.fencing.enabled' docs/runbook.md   # both, under #rb-fenced-out
grep -rn '08-operations.md#rb-' docs/06-observability-and-slo.md    # expect NO output
grep -rn 'documentation' deploy/terraform | grep -c 'runbook.md#rb-'
```

## 7. Out of scope

Firing the alerts and measuring detection latency (**T-069** — it also stamps the `Last executed:` lines). Changing an alert condition, threshold or severity (T-066 owns the policies; a threshold you disagree with is a finding for T-069, not an edit here). Rewriting `08 §8.2` entries into the new file's voice. Routine-ops procedures beyond what an entry needs — `08 §8.3` stays where it is and is linked, not copied. The postmortem and toil-register updates (T-069).

## 8. Hazards

- **The anchor mismatch is the whole trap.** `08 §8.2` insists names match `06 §6.7` "one-for-one, both directions". The twelve-for-twelve mapping now holds on disk (`06 §6.7` gained the payout-burn and token-regression rows, `08 §8.2` gained entries 11 and 12) — **re-verify it by grep before writing**, and never fix a residual mismatch by renaming a `#rb-` anchor that another document already deep-links.
- Entries 11 and 12 are the youngest prose in `08 §8.2` and the only two never executed against a real incident; treat their diagnostics as unproven until T-069 runs them. Do not pad them with etcd folklore — three diagnostics that actually run beats a page of theory.
- Cloud Monitoring `documentation` fields render limited Markdown; a relative `docs/runbook.md#…` link is useless in a notification. Use the repository URL form the other policies already use, or state plainly that the link is repo-relative.
- Entry 8's remediation order (compact → defragment, one member at a time) and entry 7's member-replacement order (remove → add → start with `--initial-cluster-state existing`) are both correctness-critical; copy them from `08 §8.3`, do not reconstruct from memory.

## 9. On completion

Mark the T-068 row done in `tasks/README.md`. Record: the exact list of `06 §6.7` links repointed, the anchors you had to invent for entries 11–12, whether the Terraform `documentation` fields existed to edit, and any entry you could not write without inventing a contract identifier (that one is a contract-amendment request, not a gap to fill).
