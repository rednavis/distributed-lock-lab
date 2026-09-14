# T-065 — Alert policies in Terraform

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 · **Estimate** 45 min — **over budget for one session; split as follows.** **T-065a**: the two notification channels, the `locals`/`variables` additions, and the **nine non-burn-rate policies** (the three correctness policies, backlog age, lease expiry, `wal_fsync` p99, leader changes, Cloud SQL saturation, metrics absent), each with its complete five-part `documentation`. **T-065b**: the **three dual-window burn-rate policies** — S1 fast, S1 slow, S6 payout-success — six conditions in total plus the three hardest `documentation` blocks. Do a first, in one session; b assumes a. The catalogue grew from ten rows to **twelve** ([06 §6.7](../docs/06-observability-and-slo.md#obs-alerts) gained payout-success fast burn and token regression), and twelve five-part documentation blocks — the writing, not the HCL — no longer fit thirty minutes, so treat the split as **expected, not hypothetical**. It remains **one ledger row with a named split trigger**, not a second counted session: the mandatory two-row splits stay T-016, T-034 and T-043, and the plan total stays 66 ([10 §10.2](../docs/10-delivery-plan.md#dp-milestones)).

**Preconditions** — T-064 (module `deploy/terraform/modules/observability` exists with the custom service, six SLO objects and the `locals` mirror; SLO ids are exported), T-060…T-063 (all C4 metrics and the log-based metrics are queryable), T-056…T-059 (services deployed). You inherit **SLOs that nobody is watching**: attainment is computed, but nothing pages.

**Goal** — Materialise all twelve alerts of doc 06 §6.7 as `google_monitoring_alert_policy` resources whose `documentation` field is written to be read at 3 a.m. and whose first line links the owning runbook anchor in doc 08.

## 1. Why this task exists

The alert catalogue already decides the hard part — which signals page and which only ticket, and why a cause-based signal never pages (§6.7). What is missing is the mechanical part plus the human part: the text a woken engineer reads first. NFR-10/11 and the M6 exit criterion require **every alert to link a runbook section**, so an alert without a documented next action is an incomplete deliverable, not a stylistic gap.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Metric names and tag value sets used in every condition | `docs/contracts/C4-observability.md#ct4-metrics` |
| The two must-be-zero counters and their single-data-point alerting rule | `#ct4-zero`, doc 06 `#obs-not-slo` |
| Log-based metric names behind log-derived conditions | `#ct4-lbm` |
| Metric type + `prometheus_target` resource form | `#ct4-scrape`, doc 06 `#obs-empty` |
| Alert rows: signal, condition, severity, page-vs-ticket, runbook anchor | doc 06 `#obs-alerts` (authority) |
| Burn-rate thresholds **13.4× / 5.6×** (the 28-day-window values; **not** the 30-day 14.4× / 6× constants) and the S4 sub-budget of 60 | doc 06 `#obs-alerts`, `#obs-arithmetic` |
| Runbook anchors `#rb-*` | `docs/08-operations.md` |
| Terraform layout, project/region names | ADR-008 D1, `C5-config-build-and-naming.md#ct5-naming` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report** (doc 04 §4.4). In particular: never invent a metric or log-based-metric name to make a condition compile.

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/observability/alerts.tf` | The twelve `google_monitoring_alert_policy` resources |
| `deploy/terraform/modules/observability/notification.tf` | Two `google_monitoring_notification_channel` resources: **page** and **ticket** (email placeholders are acceptable for a project; the split must be real) |
| `deploy/terraform/modules/observability/locals.tf` (modify) | Add burn-rate thresholds, `for:` durations and severity labels to the existing mirror — still the only home for numbers |
| `deploy/terraform/modules/observability/variables.tf` (modify) | Notification addresses as inputs; no address literal in `alerts.tf` |
| `docs/08-operations.md` (modify only if an anchor is missing) | Add the missing `#rb-*` heading anchor; do **not** rewrite runbook prose |

## 4. Specification

**One policy per §6.7 row**, twelve in total, each named `dlock-<signal-slug>` and carrying `user_labels` for `severity` (`s1`…`s3`) and `signal_class` (`symptom`, `cause`, `leading`, `meta`). The label set is what makes the page/ticket routing auditable.

| Policy | Condition shape the implementer must produce |
|---|---|
| Fenced-out write | threshold `> 0` on `lock_fenced_out_total` increase over 1 min, **no** `for:` duration, any `resource` tag |
| Duplicate rail submission | same shape on `rail_duplicate_attempted_total` |
| Token regression | threshold `> 0`, **no** `for:` duration, on the token-monotonicity signal of §6.7 — the log-derived condition, since the token may not be a metric label (`#ct4-cardinality`). Routed to the page channel at the highest severity; `documentation` must open with "stop the writers" |
| Fast budget burn | **two** conditions ANDed: S1 burn ≥ **13.4×** over 1 h **and** ≥ 13.4× over trailing 5 min |
| Slow budget burn | **two** conditions ANDed: S1 burn ≥ **5.6×** over 6 h **and** ≥ 5.6× over trailing 30 min; business-hours page channel |
| Payout-success fast burn | **two** conditions ANDed: **S6** burn ≥ **13.4×** over 1 h **and** ≥ 13.4× over trailing 5 min — the S6 SLO id from T-064's output map, never the S1 one |
| Backlog age | `payout_backlog_age_seconds` > 300 for 10 min |
| Lease expiry rate | `rate(lock_lease_expired_total[10m])` above 3× the trailing 7-day median for 15 min — if the median form is not expressible, substitute a documented static threshold and say so in §9 |
| etcd `wal_fsync` p99 | `etcd_disk_wal_fsync_duration_seconds` p99 > 100 ms for 10 min |
| etcd leader changes | `increase(…leader_changes_seen_total[1h]) ≥ 3`, plus a second condition on the 28-day total exceeding 60 (the S4 sub-budget) |
| Cloud SQL connection saturation | `num_backends ÷ max_connections > 0.8` for 5 min, filtered to `dlock-pg-lock` |
| Metrics absent | absence of `lock_acquire_seconds_count` for 10 min while ≥ 1 replica is ready |

**The `documentation` field is the deliverable, not decoration.** Fixed five-part structure, in this order, ≤ 12 lines, plain sentences, no jargon a tired reader must decode:
1. **What is true right now** — one sentence in the indicative ("A write was rejected because it carried a stale fencing token").
2. **Why you were woken / why this is only a ticket** — the money or budget consequence, one sentence.
3. **First action** — the single command or query to run first.
4. **Runbook** — a markdown link to the exact `docs/08-operations.md#rb-*` anchor from the §6.7 row.
5. **If it is a false alarm** — the condition under which the right outcome is to retune or delete the alert (§6.10 makes deletion a success).

For the two **counter-based** correctness policies (fenced-out write, duplicate rail submission), the documentation must state explicitly that the safety net **held** and that the incident is closed on *why a zombie holder existed*, never on "the guard worked". The third correctness policy, **token regression**, is the inverse and must never borrow that wording: there the fence is decoration, nothing was caught, and the documentation opens with "stop the writers".

**Channel routing:** S1 and the two S2 pages → page channel; S2 slow burn → page channel marked business-hours in documentation; S3 → ticket channel. `auto_close` set long enough that a latching alert is visible, and documented.

## 5. Acceptance criteria

1. `grep -c 'resource "google_monitoring_alert_policy"' alerts.tf` returns **12**.
2. Every policy has a non-empty `documentation.content` containing the literal string `docs/08-operations.md#rb-`.
3. Every `#rb-*` anchor referenced resolves to a heading that exists in `docs/08-operations.md` (checked by grep per anchor).
4. Every policy carries `severity` and `signal_class` user labels; no policy is labelled both `cause` and routed to the page channel.
5. All **three** correctness policies (fenced-out write, duplicate rail submission, token regression) have **no** duration/`for` window — single data point, per `#ct4-zero` for the two counters and doc 06 §6.7 for the log-derived token-regression condition.
6. All **three** burn-rate policies (S1 fast, S1 slow, S6 payout-success) contain two conditions combined with AND (long window + short window at the same multiplier), and the multipliers in `locals` are **13.4** and **5.6**, not 14.4 and 6.
7. No burn-rate number, duration or threshold literal appears in `alerts.tf`; all come from `locals`.
8. `terraform validate` and `plan` succeed; `apply` creates **12** policies and 2 channels (after T-065a alone: 9 policies and 2 channels).

## 6. Verification

```
terraform -chdir=deploy/terraform/envs/dev plan -out=tfplan && terraform -chdir=deploy/terraform/envs/dev apply tfplan
gcloud alpha monitoring policies list --project dlock-lab --format='table(displayName,enabled,userLabels.severity)'
gcloud alpha monitoring policies list --project dlock-lab --format='value(documentation.content)' | grep -c '08-operations.md#rb-'
grep -n '#rb-' docs/06-observability-and-slo.md | wc -l   # cross-check the count matches
```
Expected: twelve enabled policies with severity labels; the grep count equals twelve (doc 06 §6.7 carries exactly twelve `#rb-` links, one per row). Read one page policy's documentation end to end and ask whether it would be actionable half-asleep — if not, rewrite it now, not in T-068.

## 7. Out of scope

Actually firing the alerts and recording detection latency (**T-068/069** — §6.10 is their gate), the dashboard (**T-066**), tracing (**T-067**), new runbook prose (doc 08 already owns it), and changing any SLO target (**T-064**).

## 8. Hazards

Two dialects in one file: Cloud Monitoring conditions and PromQL-native rules look interchangeable and are not (ADR-009 consequences) — pick per condition and comment which. The absence policy is the alert that guards the alerts (§6.7): if it is mis-scoped, every ratio alert above it is silently disarmed and the dashboard looks calm. And a condition whose filter matches nothing never fires and never errors (§6.1.1) — the only proof of life is T-068.

## 9. On completion

Mark the T-065 row done in `tasks/README.md` — or `split`, naming which of the three burn-rate policies remain, if the session stopped after T-065a. Record any condition you had to express as a static threshold instead of the doc-06 form (the lease-expiry median is the likely one), and list any `#rb-*` anchor you had to add to doc 08.
