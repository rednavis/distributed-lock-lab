# T-050 — Terraform root and dev environment

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 (GCP infrastructure) · **Estimate** 30 min (write-only; no `apply` — first real apply is T-054+)

**Preconditions** — T-001…T-008 (repo skeleton, `settings.gradle.kts`, version catalog, `tasks/README.md`
ledger) and M1–M4 complete. You inherit a monorepo whose eight Java modules build and whose fencing
experiment already passes locally (T-042); `deploy/terraform/` does not exist yet. This is the first M5 task.

**Goal** — Create the thin `envs/dev` Terraform root: GCS backend, pinned provider block, variables,
locals and the label set every later module inherits, plus the documented one-time bootstrap. Because
NFR-14 requires the cost guard to exist **before the first apply**, this task also creates
`modules/observability/` and the only `google_billing_budget` + notification channel in the tree.

## 1. Why this task exists

Terraform state is the only record of what is billable, so the backend and its versioning must exist
before the first resource does — the bootstrap chicken-and-egg of [05 §5.5](../docs/05-infrastructure.md#gcp-state).
Fixing the variable, locals and label vocabulary now means T-051…T-059 add `module` blocks and nothing
else. The budget lives here for the same ordering reason: the first apply that can cost money is
T-053's Cloud SQL, so an alert introduced any later is an alert introduced after the risk
([05 §5.10](../docs/05-infrastructure.md#gcp-cost), NFR-14). Pinning `~> 1.15` / `google ~> 7.0` here is what stops a later session silently resolving a 6.x
provider whose Autopilot arguments differ.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Layout `deploy/terraform/{modules,envs/dev}`, state bucket | [C5 §5.5](../docs/contracts/C5-config-build-and-naming.md#ct5-layout) |
| Terraform 1.15, google provider 7.x | [C5 §5.3](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |
| `dlock-lab`, `europe-central2`, `dlock-` resource names, snake_case module names, local name `this` | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Secret names, no credential in a file | [C5 §5.7](../docs/contracts/C5-config-build-and-naming.md#ct5-env) |
| Layout/state/pinning rules D1–D8, operator command lines | [ADR-008](../docs/adr/ADR-008-terraform-layout-state-and-provider-versions.md#adr8-decision) |
| `google_billing_budget "this"` lives in `module "observability"`; thresholds 50/90/100 % with `all_updates_rule`; the email notification channel is declared first | [05 §5.4](../docs/05-infrastructure.md#gcp-tf) |
| Budget amount EUR 50/month, email channel, alert configured **before** the first apply (NFR-14) | [05 §5.10](../docs/05-infrastructure.md#gcp-cost) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report** the mismatch
quoting both, per [04 §4.4](../docs/04-contracts.md#c-precedence). ADRs are not contracts; a spec-vs-ADR
conflict is resolved in favour of the contract and recorded in §9.

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/envs/dev/backend.tf` | `backend "gcs"` block: bucket, `prefix = "dev"` |
| `deploy/terraform/envs/dev/versions.tf` | `required_version`, `required_providers`, nothing else |
| `deploy/terraform/envs/dev/providers.tf` | `provider "google"` with project/region from vars, default labels |
| `deploy/terraform/envs/dev/variables.tf` | every input, each with `description` and `type`; defaults only where the value is lab-fixed |
| `deploy/terraform/envs/dev/locals.tf` | derived names + the label map |
| `deploy/terraform/envs/dev/main.tf` | no `resource` blocks; exactly one `module "observability"` block, plus a header comment naming the modules T-051…T-059 will wire in |
| `deploy/terraform/envs/dev/outputs.tf` | `notification_channel_id` from the observability module; otherwise a placeholder populated by later tasks |
| `deploy/terraform/modules/observability/main.tf` | New. One `google_monitoring_notification_channel "email"` and one `google_billing_budget "this"` — the whole cost guard, nothing else |
| `deploy/terraform/modules/observability/variables.tf` | New. `project_id`, `labels`, `billing_account`, `budget_amount_eur` (default 50), `notification_email`; no default for `billing_account` or `notification_email`. T-059 and T-063/T-064 extend this file |
| `deploy/terraform/modules/observability/outputs.tf` | New. `notification_channel_id`, consumed by T-059 and T-065. T-063/T-064 extend it |
| `deploy/terraform/envs/dev/dev.auto.tfvars` | non-secret values only |
| `deploy/terraform/envs/dev/.gitignore` | `.terraform/`, `*.tfstate*`, `tfplan` |
| `deploy/terraform/README.md` | bootstrap + daily commands, destroy order pointer, the "never applied directly" rule for modules |

## 4. Specification

**Variables.** `project_id` (default `dlock-lab`), `region` (default `europe-central2`), `zone`,
`subnet_cidr`, `pods_cidr`, `services_cidr`, `psa_cidr`, `sql_tier`, `notification_email`,
`budget_amount_eur`, `billing_account`, `env` (default `dev`). No variable may have a default that is a
secret, an email, or a billing account — those are supplied per operator. Do **not** add variables for
anything on [C5 §5.8](../docs/contracts/C5-config-build-and-naming.md#ct5-fixed).

**Locals.** `name_prefix = "dlock"`; a single `labels` map applied everywhere, keys
`part-of = dlock-lab`, `env`, `managed-by = terraform`, `component`. GCP labels are lowercase with
hyphens — mirror the K8s label vocabulary of C5 §5.6 rather than inventing a second one.

**Provider.** Set `default_labels` from `local.labels` so no child module repeats them. `user_project_override`
not required. Do not configure `google-beta` until a task actually needs it.

**Budget (the reason this task, not a later one, owns it).** In `modules/observability/`: one
`google_monitoring_notification_channel "email"` declared before the budget that references it, and one
`google_billing_budget "this"` scoped to project `dlock-lab` at `budget_amount_eur` (ASSUMPTION: EUR
50/month). Threshold rules 50 % / 90 % / 100 % of current spend; `all_updates_rule` wired to the channel
with default IAM recipients disabled, so the declared address is the only destination. The forecasted-spend
rule and `deletion_protection` threading are T-059 — this task ships the minimum that satisfies NFR-14
before T-053 creates the first billable resource. Record in a comment that a budget is an *alert*, not a
cap: GCP does not stop the resources. **This is the only budget resource in the tree** — no later task adds
a second one.

**Backend.** Bucket per C5 §5.5. Object versioning and uniform bucket-level access are properties of the
bootstrapped bucket, not of this code — state that in the README so the next reader does not look for them here.

**README.** Reproduce the ADR-008 §8.2 command block verbatim, in order, and state the rule that every
`apply` goes through `plan -out=tfplan`. Add one paragraph: state is assumed to contain plaintext DB
passwords, the bucket is private, and nothing under `envs/` is ever committed except source.

## 5. Acceptance criteria

1. `terraform -chdir=deploy/terraform/envs/dev fmt -check` exits 0.
2. `versions.tf` contains `required_version` matching `~> 1.15` and `hashicorp/google` at `~> 7.0`; no other provider is declared.
3. `backend.tf` names the bucket exactly as C5 §5.5 spells it and `prefix = "dev"`.
4. Every variable in `variables.tf` has a non-empty `description`; `grep -c description` equals the variable count.
5. No file under `deploy/terraform/` contains a password, an `@`-address default, or a billing-account literal.
6. `envs/dev/main.tf` declares zero `resource` blocks and exactly one `module` block (`observability`).
7. `deploy/terraform/README.md` contains all six ADR-008 operator command lines.
8. Exactly one `google_billing_budget` exists under `deploy/terraform/` (`grep -rc` across the tree returns 1), with three threshold rules and an `all_updates_rule` referencing the one channel this task declares. (T-065 adds separate **page** and **ticket** channels for alert policies; those are not the billing channel and do not conflict.)
9. `billing_account` and `notification_email` have no defaults — a `plan` without them fails on missing required variables.

## 6. Verification

Run `terraform -chdir=deploy/terraform/envs/dev fmt -check` and `terraform -chdir=deploy/terraform/envs/dev validate`.
`validate` requires `init`; because the bucket may not exist yet, run
`terraform -chdir=deploy/terraform/envs/dev init -backend=false` first and say so in the ledger note.
Expected: `Success! The configuration is valid.` Then
`grep -rn "password\|@\|billing_account" deploy/terraform/envs/dev` — expected: only variable
declarations and comments, no literal values. Also
`grep -rc 'google_billing_budget' deploy/terraform/ | grep -v ':0'` — expected: one file, count 1. Do **not** run `plan`, `apply`, or any `gcloud` command in this task;
the budget is *written* here and applied with the first real apply in T-053, which is what NFR-14 asks for.

## 7. Out of scope

Every other `module` block and resource: `network` is T-051, `cloudsql` T-052, `gke` T-053. Artifact
Registry, k8s manifests, and the real `init`/`apply` against GCP belong to T-054…T-059. Inside
`modules/observability/`, this task ships only the channel and the budget: the forecasted-spend threshold,
`deletion_protection` threading and the teardown runbook are T-059; log-based metrics are T-063; SLO
objects are T-064; alert policies are T-065.
Do not create `envs/prod` (ADR-008 D7).

## 8. Hazards

- **The state-bucket name is contradictory across the doc set.** [C5 §5.5](../docs/contracts/C5-config-build-and-naming.md#ct5-layout) and ADR-008 D3 say `gs://dlock-tfstate`; [05 §5.5](../docs/05-infrastructure.md#gcp-state) says `dlock-lab-tfstate`. **The contract wins** — use the C5 spelling and report the discrepancy so 05 §5.5 can be amended.
- `init` without `-backend=false` will try to reach a bucket that does not exist and fail confusingly.
- `required_version = ">= 1.15"` is not the pin ADR-008 D4 asks for; pessimistic `~>` is.
- A budget needs a **billing-account-level** permission the project-level bootstrap role does not grant; if `plan` later rejects it, that is an IAM gap to record, not a reason to defer the resource past the first apply.
- Declaring the notification channel after the budget that references it reads fine but makes the dependency implicit — declare the channel first, as [05 §5.4](../docs/05-infrastructure.md#gcp-tf) pins.
- Commit `.terraform.lock.hcl` for every platform the project supports; it is the only thing making provider resolution reproducible. The committed-lock-file rule of D5 applies later.

## 9. On completion

Mark the T-050 row done in `tasks/README.md`. Note the state-bucket-name deviation and the
`-backend=false` workaround, and whether `.terraform.lock.hcl` was produced (it is not yet committable).
