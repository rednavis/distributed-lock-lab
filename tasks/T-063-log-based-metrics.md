# T-063 — Log-based metrics in Terraform

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 — Observability and SRE · **Estimate** 30 min (one small module, five metric resources
that differ only in filter and labels; the plan/apply loop is most of the time)

**Preconditions** — T-062 (all eleven events emit as JSON with C4 field names, verified in Cloud Logging),
T-061 (scrape path proven, so the Prometheus twins exist to cross-check against), and T-050…T-053, which
gave you `deploy/terraform/envs/dev/` with a GCS backend, pinned providers, and modules wired from
`main.tf`. You inherit an environment with zero `google_logging_*` resources.

**Goal** — Declare the five C4 §4.7 log-based metrics as Terraform, with label extractors that promote
only fields C4 §4.6 permits.

## 1. Why this task exists

Two of the project's most important signals — a fence firing and a blocked duplicate rail submission — are
invariant violations whose healthy value is exactly zero, and the log pipeline is a *second, independent*
path to them: if Managed Prometheus scraping is misconfigured, the log-derived counter still fires. Putting
them in Terraform rather than clicking them into the console is what makes the promotion decision reviewable,
because promoting `payoutId` to a label in a console UI reproduces the cardinality explosion of C4 §4.3 in
the one place no code review looks.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The five derived metric names, their exact Cloud Logging filters, and their label lists | [C4 §4.7](../docs/contracts/C4-observability.md#ct4-lbm) |
| Which fields may be promoted to labels, and the never-promote list (`token`, `payoutId`, `accountId`, `lockKey`, `idempotencyKey`, `railRef`, `traceId`, …) | [C4 §4.6](../docs/contracts/C4-observability.md#ct4-promotion) |
| The `jsonPayload.event` values and field names the filters match on | [C4 §4.5](../docs/contracts/C4-observability.md#ct4-logs) |
| The two counters whose healthy value is exactly zero, and why `> 0` with no window is correct | [C4 §4.4](../docs/contracts/C4-observability.md#ct4-zero) |
| The Micrometer twins these cross-check, which remain the alert source where they exist | [C4 §4.2](../docs/contracts/C4-observability.md#ct4-metrics) |
| Project `dlock-lab`, region `europe-central2`, module layout under `deploy/terraform/` | [C5 §5.5](../docs/contracts/C5-config-build-and-naming.md#ct5-layout), [§5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop, quote both, report.
Concretely: this task was briefed with the shorthand names *lock_fenced_out* and
*duplicate_rail_submission*; **C4 §4.7 pins `lock_zombie_write_attempts` and `rail_duplicate_blocks`, and
those are the names to create.** `lock_fenced_out_total` is the *Micrometer* export of a different pipeline
and must not be shadowed by a log-based metric of the same name.

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/observability/versions.tf` | google provider requirement only |
| `deploy/terraform/modules/observability/variables.tf` (modify — T-050 created it) | Add a map/flag input for which metrics to create; `project_id` and `labels` are already declared, so do not re-declare them, and no filter is ever a variable |
| `deploy/terraform/modules/observability/logging_metrics.tf` | The five `google_logging_metric` resources of §4 |
| `deploy/terraform/modules/observability/outputs.tf` (modify — T-050 created it) | Add the created metric names, for later alert policies to reference |
| `deploy/terraform/modules/observability/README.md` | The promotion rule in three sentences, the never-promote list, and why a log-based metric beats a log query alert (C4 §4.7) |
| `deploy/terraform/envs/dev/main.tf` (modify) | Pass the new input to the existing `module "observability"` block T-050 wired — do not add a second block |
| `deploy/terraform/envs/dev/outputs.tf` (modify) | The metric names |
| `docs/06-observability-and-slo.md` (modify) | A table: derived metric → filter → labels → the Micrometer twin it cross-checks (or "no twin") |

## 4. Specification

**Five resources, one per C4 §4.7 row.** Each is a `google_logging_metric` with: `name` exactly as tabled;
`filter` matching `jsonPayload.event="<event>"` for that row's event; `metric_descriptor` of kind DELTA /
value type INT64 with one `labels` block per permitted label; and a `label_extractors` entry per label
reading the corresponding `jsonPayload` field. Every filter additionally constrains the log to the project's
own workloads (`resource.type="k8s_container"` plus the cluster/namespace of M5) so an unrelated project
resource cannot inflate a must-be-zero counter.

| Derived metric | Event filtered on | Labels (and only these) |
|---|---|---|
| `lock_zombie_write_attempts` | `fenced_out` | `service`, `resource` |
| `rail_ambiguous_outcomes` | `rail_ambiguous` | `service` |
| `rail_duplicate_blocks` | `duplicate_rail_submission_attempted` | `service` |
| `lock_revocations` | `lock_revoked` | `service`, `reason` |
| `client_session_losses` | `session_lost` | `service`, `backend` |

**Label extractors are the whole risk surface.** Each extractor is a straight field read of a bounded
field; no regex capture over a message, no extractor for any field on the C4 §4.6 never-promote list.
`presentedToken`, `highestToken`, `resourceId`, `payoutId` and `idempotencyKey` stay in the payload where
they are queryable for free — they are *why* a fence fired, not a dimension to aggregate by.

**No value extractor.** These are event counters, not distributions: leave `value_extractor` unset so each
matching entry counts one. A distribution over `elapsedMillis` would be a new signal and needs a C4 row.

**Descriptions carry the operator instruction.** Each `metric_descriptor.display_name`/description states
the healthy value and what non-zero means, in one line — for the two invariant counters, that healthy is
*no data at all* and that the alert is `> 0` with no duration window (C4 §4.4). This is the text an
on-caller reads in the console at 03:00; it is a deliverable, not a comment.

**Independence, stated.** The README records that `lock_zombie_write_attempts` and `rail_duplicate_blocks`
duplicate `lock.fenced.out` and `rail.duplicate.attempted` **on purpose**, from a different pipeline, and
that where a Micrometer twin exists the twin is the alert source and the log metric is the cross-check.
`client_session_losses` and `rail_ambiguous_outcomes` have no twin at their emitter, so they stand alone.

## 5. Acceptance criteria

1. `terraform -chdir=deploy/terraform/envs/dev fmt -check -recursive` exits 0 and `validate` reports valid.
2. `grep -c 'resource "google_logging_metric"' deploy/terraform/modules/observability/logging_metrics.tf` equals 5, and all five C4 §4.7 names appear verbatim.
3. `grep -rnE 'payoutId|accountId|lockKey|idempotencyKey|railRef|presentedToken|highestToken|resourceId|traceId|spanId|token' deploy/terraform/modules/observability` returns no hit inside a `label_extractors` or `labels` block.
4. Every `filter` contains `jsonPayload.event=` and a `resource.type` constraint; no filter matches on message text.
5. No resource sets `value_extractor`.
6. The label set of each resource equals the §4 table exactly — no `severity`-only extras, no missing label.
7. Each of the five descriptions names the healthy value; the two invariant counters say "healthy = no data points".
8. `docs/06-observability-and-slo.md` table lists all five with their twin or "no twin".

## 6. Verification

`terraform -chdir=deploy/terraform/envs/dev init -backend=false && terraform -chdir=deploy/terraform/envs/dev validate`
— valid. `terraform -chdir=deploy/terraform/envs/dev plan` — expect exactly five resources to add and
**no** changes to Cloud SQL, GKE or network resources. After apply:
`gcloud logging metrics list --project dlock-lab --format='table(name,filter)'` — expect the five names.
Then run the fencing scenario against the cluster (the T-042 experiment, cluster edition), and
`gcloud logging read 'jsonPayload.event="fenced_out"' --project dlock-lab --limit 1` — expect one entry;
then query `logging.googleapis.com/user/lock_zombie_write_attempts` in Metrics Explorer — expect a point
within a minute, labelled with `service` and `resource` and **nothing else**.

## 7. Out of scope

Alert policies, notification channels, SLOs, burn-rate windows and dashboards (T-064 onwards) — this task
only creates the signals they will reference. Log sinks, log buckets, retention and exclusion filters.
Uptime checks. Any change to the emitted log schema: if a field is missing, T-062 owns it and the correct
move is to stop and report, not to regex it out of the message.

## 8. Hazards

- **Promotion in a console UI is invisible to review** (C4 §4.6) — the reason this is Terraform.
- A filter typo yields a metric that is permanently zero, which on a must-be-zero counter is
  indistinguishable from health (C4 §4.2, §4.4). Always prove a point *arrives* before trusting the metric.
- Naming a log-based metric after a Micrometer export collides two pipelines in one dashboard and hides
  the disagreement that makes the cross-check valuable.
- Omitting the `resource.type`/namespace constraint lets unrelated project logs increment an alerting
  counter; you will chase a fence that never fired.
- A label declared in `metric_descriptor` with no matching `label_extractors` entry applies as an empty
  string on every point and quietly ruins grouping.

## 9. On completion

Mark the T-063 row done in `tasks/README.md`. Record the five created metric names, whether the fenced-out
point was observed end to end, and any C4 §4.7 row you could not implement as tabled.
