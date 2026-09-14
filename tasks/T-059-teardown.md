# T-059 — Teardown, deletion-protection threading and orphan verification

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 (GCP infrastructure) · **Estimate** 30 min for the Terraform edits plus the runbook. The
first *real* destroy rehearsal costs a day of cloud time and is deliberately **not** in this session — it is
the closing step of the one-focused-day pattern, run by the operator.

**Preconditions** — T-050…T-058. You inherit the full `deploy/terraform/` stack (`network`, `sql_lock`, `sql_pay`,
`gke`, `artifacts`, `observability`), the Kubernetes manifests, the deploy runbook and
`deploy/console-walkthrough.md`. **T-050 created `modules/observability/` with the notification channel and
the `google_billing_budget "this"`** — the cost guard already exists and already applied, as NFR-14 requires.

**Goal** — Finish the cost guard with its forecasted-spend threshold, thread `deletion_protection` so the
destroy is one `apply` away, pin the destroy order that actually works, and write the by-hand orphan sweep
that exists because Terraform state only knows what Terraform created.

## 1. Why this task exists

Two lines on this bill charge for *existing* rather than for being used — the regional cluster management fee
and Cloud NAT — so an unnoticed stack costs ~EUR 12/day while nobody is looking
([05 §5.10](../docs/05-infrastructure.md#gcp-cost)). The budget alert that catches that is already in
place — T-050 put it there precisely so it preceded the first apply (NFR-14); what is missing is the
**forecasted-spend** rule, which warns on day one of an accidentally standing environment instead of on day
fifteen. The destroy half is separate work because `destroy` is not `apply` in reverse: deletion protection
blocks it, PSA peering hangs it, and retained PVC disks survive it entirely.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| `google_billing_budget "this"` lives in `module "observability"` and is created by **T-050**; this task only adds the forecasted-spend threshold to it | [05 §5.4](../docs/05-infrastructure.md#gcp-tf), [T-050](T-050-tf-root.md) |
| The six-step teardown order and the orphan sweep command set | [05 §5.12](../docs/05-infrastructure.md#gcp-teardown) |
| Trap 2 (PSA destroyed while an instance uses it) and trap 3 (`deletion_protection` defaults true) | [05 §5.6](../docs/05-infrastructure.md#gcp-traps) |
| State bucket `gs://dlock-tfstate` sits **outside** the managed stack and is never destroyed | [C5 §5.5](../docs/contracts/C5-config-build-and-naming.md#ct5-layout) (bucket name), [05 §5.5](../docs/05-infrastructure.md#gcp-state) (outside the stack) |
| Resource names in every command (`dlock-lab`, `dlock-pg-lock`, `dlock-pg-pay`, `dlock-gke`, namespace `dlock`) | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Module/local naming (`observability`, local name `this`) | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Zero billable resources after teardown = SC-07 | [00 §Success criteria](../docs/00-charter.md#ch-success) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**, per
[04 §4.4](../docs/04-contracts.md#c-precedence).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/observability/main.tf` | Modify the budget **T-050 created**: add the fourth threshold rule — 100 % of **forecasted** spend. Change nothing else; the channel, the three current-spend thresholds and the disabled IAM recipients are already there, and **a second budget is never added**. |
| `deploy/terraform/envs/dev/*.tf` | Modify. A `deletion_protection` variable (default `true`) threaded to both Cloud SQL instances and the GKE cluster, so step 1 of teardown is one `apply`. |
| `deploy/terraform/envs/dev/dev.auto.tfvars` | Modify only if the threading needs a non-secret default surfaced; the budget inputs were already surfaced by T-050. |
| `deploy/teardown.md` | New. The ordered destroy runbook, the orphan sweep, and the zero-spend proof. |
| `deploy/README.md` | Modify: link `teardown.md` next to the walkthrough. |
| `tasks/README.md` | Modify: mark the T-059 row done. |

## 4. Specification

**Budget — one addition only.** The `google_billing_budget "this"` and its
`google_monitoring_notification_channel "email"` already exist in `module "observability"` from T-050, with
threshold rules at 50 % / 90 % / 100 % of `budget_amount_eur` on **current spend** and default IAM
recipients disabled. Add the fourth rule: 100 % of **forecasted** spend. Do not restate the variables, do
not re-declare the channel, and do not add a second budget resource — one project takes one budget, and two
double the mail while hiding which fired.

**`deletion_protection` threading.** One `deletion_protection` variable in `envs/dev`, default `true`,
passed to both Cloud SQL modules and the GKE module so that step 1 of the teardown is a single
`apply -var deletion_protection=false` rather than three hand-edits under time pressure.

**`deploy/teardown.md`** carries, in order:

| Section | Content |
|---|---|
| Why teardown is a first-class procedure | Cluster fee + NAT charge for existing; the recommended apply→run→destroy day |
| The ordered destroy | The six [§5.12](../docs/05-infrastructure.md#gcp-teardown) steps as a table — command, why this position, expected duration, what a hang means. Step 1 is `apply -var deletion_protection=false`; step 2 deletes namespace `dlock`; step 3 targets both SQL modules; step 4 is the unqualified destroy. |
| Orphan sweep | One row per sweep command (`sql instances`, `container clusters`, `compute disks`, `compute addresses`, `compute routers`, `artifacts repositories`, plus `compute forwarding-rules` and `secrets`), each with the expected empty output and the reason that class survives — retained PVC disks and reserved addresses are the usual survivors |
| Why state is not an inventory | Console-clicked resources (see the walkthrough), `kubectl`-created load balancers, PVCs whose reclaim policy retained them, anything created after the last successful apply |
| The nuclear option | `gcloud projects delete dlock-lab` as the guaranteed-complete teardown, and the explicit note that `gs://dlock-tfstate` is deliberately outside the stack — deleting the project also removes the bucket, so export any state or artifact worth keeping first |
| Zero-spend proof | The billing report read the **next morning** — the only evidence for SC-07 — and where to record it |
| Recovery | What to do when `destroy` half-fails: re-run, then `terraform state list` versus reality, then targeted destroy, then by-hand delete and `state rm` as the last resort |

No HCL bodies in the runbook; operator command lines only.

## 5. Acceptance criteria

1. Still exactly one `google_billing_budget` resource under `deploy/terraform/` (`grep -c` across the tree returns 1) — the count is unchanged from T-050.
2. That resource now declares four threshold rules — 50/90/100 % current and 100 % forecasted — with the `all_updates_rule` still referencing the T-050 email channel.
3. No variable declaration was duplicated: `grep -c 'variable "budget_amount_eur"'` across the tree is still 1.
4. A `deletion_protection` variable defaults to `true` and reaches both SQL instances and the cluster.
5. `deploy/teardown.md` exists with the six destroy steps in [§5.12](../docs/05-infrastructure.md#gcp-teardown) order and at least eight orphan-sweep rows.
6. The runbook states that the state bucket is outside the stack and that project deletion removes it.
7. `dev.auto.tfvars` still contains no real billing account id or email address.
8. `terraform validate` and `terraform fmt -check` pass in `deploy/terraform/envs/dev`.

## 6. Verification

```bash
grep -rc 'google_billing_budget' deploy/terraform/ | grep -v ':0'   # still exactly one file, count 1
grep -rn 'threshold_rules\|all_updates_rule\|FORECASTED' deploy/terraform/modules/observability/
grep -rc 'variable "budget_amount_eur"' deploy/terraform/ | grep -v ':0'  # still 1 — nothing re-declared
grep -rn 'deletion_protection' deploy/terraform/envs/dev/ deploy/terraform/modules/
cd deploy/terraform/envs/dev && terraform fmt -check -recursive && terraform validate
grep -nE 'gcloud (sql|container|compute|artifacts|secrets|projects)' deploy/teardown.md | wc -l   # ≥ 9
```
Expected: still one budget resource and one declaration of each budget variable; the forecast rule present;
`validate` clean; the sweep command count at or above nine.

## 7. Out of scope

Actually running `destroy` against a live project. Creating the budget or its channel — that is T-050, and
re-creating either here is the defect this task exists downstream of. Alert *policies* and SLO burn-rate
alerting are M6 (T-060…069); log-based metrics and SLO objects added to the same module are T-063/T-064.
The console click path is T-058. Cost figures beyond linking §5.10 belong to the M7 write-up.

## 8. Hazards

`deletion_protection` must be flipped and applied **before** destroy, not during it: a destroy that "does
nothing" is trap 3 ([05 §5.6](../docs/05-infrastructure.md#gcp-traps)), and the equivalent trap 2 —
tearing the PSA peering while an instance still peers through it — is a twenty-minute hang ending in a
half-torn stack. Second hazard: a budget is an alert. Nothing in this task stops spending, so the runbook
must never imply the guard makes an abandoned stack safe. Third: **T-050 already declared the budget** — the
temptation here is to write it again because this is the task whose title says "budget". Add the forecast
rule to the existing resource; two budgets on one project double the mail and hide which fired.

## 9. On completion

Mark the T-059 row done in `tasks/README.md` and note that M5 is complete apart from the operator's own
apply/destroy day. Record that the T-050 budget was extended with the forecast rule (not re-created), and
which modules the `deletion_protection` variable reached.
