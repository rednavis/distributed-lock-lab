# T-064 — SLO objects in Terraform

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 · **Estimate** 30 min (writing + `plan`; the first `apply` of a `google_monitoring_slo` is fiddly — if `plan` passes but `apply` rejects an SLI shape twice, stop, record the rejection in §9 and split the remaining SLIs into T-064b)

**Preconditions** — T-050…T-053 (env root `deploy/terraform/envs/dev` inits and applies; `modules/` layout in place), T-056…T-059 (all four services running in-cluster and serving traffic), T-060…T-063 (scrape verified: every metric in C4 `#ct4-metrics` is queryable in Managed Prometheus under the `prometheus_target` monitored resource, log-based metrics exist). You inherit a monitored cluster with **no SLO object anywhere** — attainment currently exists only as ad-hoc PromQL.

**Goal** — Create one `google_monitoring_custom_service` plus the six SLO objects that doc 06 §6.4 can express as continuous SLOs, each carrying its error budget in **minutes** in the description.

## 1. Why this task exists

An SLO that lives in a document is an opinion; an SLO object is the thing burn-rate alerts (T-065) subtract from. ADR-009 D3 makes every SLO a reviewed Terraform diff precisely so widening a target is visible, and D8 gives the numbers exactly one home — doc 06 — mirrored into **one** `locals` block. This task is the boundary between "we wrote targets down" and "the platform computes attainment", so it must land before alert policies have anything to reference.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Metric names, types, tag value sets (`lock_acquire_seconds*`, `payout_execute_seconds*`, `lock_session_lost_total`, `payout_backlog_age_seconds`) | `docs/contracts/C4-observability.md#ct4-metrics` |
| Prometheus-ingested series are queried as metric type `prometheus.googleapis.com/<name>/<kind>` against resource `prometheus_target` | `#ct4-scrape`, doc 06 `#obs-empty` |
| Correctness counters get **no** SLO | `#ct4-zero`, doc 06 `#obs-not-slo` |
| Targets, windows, budget minutes, bucket boundaries | doc 06 `#obs-slis`, `#obs-slos` (authority per ADR-009 D8) |
| GCP names (`dlock-lab`, `europe-central2`), Terraform layout | `docs/contracts/C5-config-build-and-naming.md#ct5-naming`, ADR-008 D1 |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report** (doc 04 §4.4). A metric name that is not in `#ct4-metrics` is not a typo to fix locally: stop.

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/observability/main.tf` (modify — T-050 created the module) | Add the `google_monitoring_custom_service` for the project; the budget and channel already in this file are not touched |
| `deploy/terraform/modules/observability/locals.tf` | The single mirror of doc 06 numbers: per-SLI target, rolling window, budget minutes, the two bucket boundaries |
| `deploy/terraform/modules/observability/slo.tf` | The six `google_monitoring_slo` resources |
| `deploy/terraform/modules/observability/variables.tf` · `outputs.tf` (modify — T-050 created both, T-063 extended them) | Add the `location`, `cluster`, `namespace` inputs (`project_id` is already declared — do not re-declare it) and outputs for the service id and a map of SLO ids (T-065 consumes it) |
| `deploy/terraform/envs/dev/main.tf` (modify) | Pass the new inputs to the existing `module "observability"` block; no SLO literals in the env root |
| `docs/06-observability-and-slo.md` (modify, one line in §6.4) | Note that the SLOs are now materialised in `modules/observability` |

## 4. Specification

**The service.** One `google_monitoring_custom_service` representing the project as a user-facing whole (display name "dlock payout path"). Rationale to record in a comment: the SLIs span four Deployments; per-Deployment services would make the payout journey unmeasurable as one thing.

**The `locals` mirror.** A single map keyed by SLI id (`s1`…`s8`) with fields: `title`, `goal`, `rolling_days`, `budget_minutes`, `threshold` (where applicable), `note`. Values come verbatim from doc 06 §6.4 — 99.9/40.3 min, 99/403 min, 99.9/1-in-1000, 99.5/201.6 min, 99/403 min, 99/403 bad minutes. No number is retyped anywhere else in this module.

**The six SLO objects.**

| SLI | Cloud Monitoring SLI shape | Notes the implementer must honour |
|---|---|---|
| S1 acquire availability | request-based `good_total_ratio` | good = `outcome="granted"`; total = `granted\|error` only — `contended` is excluded from *valid*, not counted as bad |
| S2 acquire latency ≤ 50 ms | request-based `distribution_cut`, range max **0.05** | 0.05 must be an exact bucket boundary from T-060; filter to granted acquires |
| S3 session survival | request-based ratio using `lock_session_lost_total` as the bad stream over sessions opened | Add a description sentence naming the client-side blind spot (§6.3) and pointing at `lock_lease_expired_total` as the server twin |
| S6 payout execution success | `good_total_ratio` | good = `outcome="posted"`; total = `posted\|failed\|abandoned\|ambiguous`; `ambiguous` is bad |
| S7 claim-to-posted ≤ 2 s | `distribution_cut`, range max **2** | filter to posted attempts |
| S8 backlog freshness | **windows-based** SLI over `payout_backlog_age_seconds`, good window = value < 300 | 1-minute windows; this is the gauge→ratio bridge, keep the reason in the description |

**Deliberately not SLO objects:** S4 leader elections (a *count* sub-budget = 2.0 min = 5 % of S1 — it becomes an alert in T-065 and is named in S1's description) and S5 time-to-leader (per-experiment pass/fail, owned by the harness). State both exclusions in a comment; silence would read as an omission.

**Every description field** must contain, in one line an on-call can read: target, window, and **error budget in minutes** (e.g. "99.9 % over 28 rolling days — budget 40.3 min of unavailability; S4 elections consume 2.0 min of it"). This is the only place the minutes reach the console.

## 5. Acceptance criteria

1. `deploy/terraform/modules/observability/{main,locals,slo,variables,outputs}.tf` all exist.
2. Exactly **six** `google_monitoring_slo` resources and exactly **one** `google_monitoring_custom_service` exist (`grep -c` on the module).
3. Every SLO's `display_name`/description names its SLI id (`S1`…`S8` as applicable) and contains a minutes figure matching doc 06 §6.4.
4. No numeric target, window or threshold appears in `slo.tf`; all come from `local`s (grep for `0.99`, `28`, `300` in `slo.tf` returns nothing).
5. No SLO references `lock_fenced_out_total` or `rail_duplicate_attempted_total` (§6.3.1).
6. Every metric filter uses the `prometheus.googleapis.com/…` type form with `prometheus_target` resource, not `k8s_container`.
7. `outputs.tf` exports SLO ids as a map keyed by SLI id.
8. `terraform validate` and `plan` succeed; `apply` creates all seven resources.

## 6. Verification

```
terraform -chdir=deploy/terraform fmt -recursive -check
terraform -chdir=deploy/terraform/envs/dev init && terraform -chdir=deploy/terraform/envs/dev plan -out=tfplan
terraform -chdir=deploy/terraform/envs/dev apply tfplan
gcloud monitoring services list --project dlock-lab
gcloud alpha monitoring slos list --service <service-id> --project dlock-lab
```
Expected: one custom service; six SLOs listed, each description showing budget minutes. Then open one SLO in the console and confirm attainment renders a **non-empty** series — an empty chart means the metric-type/resource pair is wrong (§6.1.1), not that the SLO is fine.

## 7. Out of scope

Alert policies and burn-rate windows (**T-065**), the dashboard JSON including the budget-remaining tile (**T-066**), tracing (**T-067**), game-day firing and detection latency (**T-068/069**), any change to histogram buckets (**T-060** owns them).

## 8. Hazards

The metric-type-vs-monitored-resource trap (doc 06 §6.1.1) fails **silently**: a syntactically valid SLO whose filter matches nothing shows 100 % attainment forever, which is worse than no SLO. Second trap: `distribution_cut` interpolates if the threshold is not a real bucket edge — verify 0.05 and 2 exist in the emitted buckets before trusting S2/S7. Third: `google_monitoring_slo` rejects some intuitive SLI shapes (ADR-009, consequences) — reformulate the SLI, never the target.

## 9. On completion

Mark the T-064 row done in `tasks/README.md` (add the M6 block if the ledger has no row yet). Record any SLI you had to reformulate to satisfy the API, and note that doc 06 §6.8/§6.9 still cite the older task numbers T-063/T-065 for dashboards and tracing — the real owners are T-066 and T-067.
