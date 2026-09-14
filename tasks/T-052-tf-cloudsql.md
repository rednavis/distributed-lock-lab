# T-052 — Terraform module: cloudsql, two instances

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 (GCP infrastructure) · **Estimate** 30 min (write + `validate` only; no `apply`)

**Preconditions** — T-050, T-051. You inherit `envs/dev` wired to `module "network"`, which now outputs
`network_self_link`, `subnet_self_link`, the two range names and `psa_connection_id`. No database resource
exists anywhere in `deploy/terraform/`. The Flyway migration trees already live inside `lock-server` and
`payment-resource` (M1/M2) and are not touched here.

**Goal** — Write one reusable `cloudsql` module and instantiate it twice as `sql_lock` (`dlock-pg-lock`,
REGIONAL) and `sql_pay` (`dlock-pg-pay`, ZONAL), private IP only, each with its own database, user and
Secret Manager password secret.

## 1. Why this task exists

The two-instance split is the project's headline infrastructure argument ([ADR-003](../docs/adr/ADR-003-two-databases-two-instances.md)):
the lock store is the availability-critical dependency and gets synchronous standby failover; the payment
database is the cheap one whose zonal outage is a demonstration, not an emergency. Encoding that as two
`availability_type` values in reviewable text is the point. The passwords must exist as Secret Manager
resources here because T-053 grants `secretAccessor` on those exact secret ids.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Instance names `dlock-pg-lock` (REGIONAL) / `dlock-pg-pay` (ZONAL); secrets `dlock-lock-db-password`, `dlock-pay-db-password` | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Databases `lockdb`, `paydb`; users `lockapp`, `payapp` | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Module block names `sql_lock`, `sql_pay`; resource local names `this` / role-named | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| PostgreSQL 16 | [C5 §5.3](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |
| Module inputs/outputs (`private_ip`, `connection_name`, `secret_id`) | [05 §5.4](../docs/05-infrastructure.md#gcp-tf) |
| Passwords reach pods as `*_PASSWORD` env vars, never a config key | [C5 §5.7](../docs/contracts/C5-config-build-and-naming.md#ct5-env) |
| Separate databases on separate instances, and why | [ADR-003](../docs/adr/ADR-003-two-databases-two-instances.md) |
| No secret values in tfvars or outputs | [ADR-008 D6](../docs/adr/ADR-008-terraform-layout-state-and-provider-versions.md#adr8-decision) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**, per
[04 §4.4](../docs/04-contracts.md#c-precedence).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/cloudsql/versions.tf` | google + random provider requirements |
| `deploy/terraform/modules/cloudsql/variables.tf` | `project_id`, `region`, `instance_name`, `availability_type`, `tier`, `db_name`, `db_user`, `secret_id`, `network_self_link`, `psa_connection_id`, `enable_pitr`, `deletion_protection`, `labels` |
| `deploy/terraform/modules/cloudsql/main.tf` | the six resources of §4 |
| `deploy/terraform/modules/cloudsql/outputs.tf` | `private_ip`, `connection_name`, `secret_id` |
| `deploy/terraform/modules/cloudsql/README.md` | the REGIONAL-vs-ZONAL argument in three sentences, the PSA ordering trap, why the password never leaves Secret Manager |
| `deploy/terraform/envs/dev/main.tf` (modify) | `module "sql_lock"` and `module "sql_pay"` |
| `deploy/terraform/envs/dev/outputs.tf` (modify) | both private IPs and both secret ids; **no password output** |

## 4. Specification

**Resources** in the module:

| Resource | Local name | Arguments that matter |
|---|---|---|
| `google_sql_database_instance` | `this` | `database_version` = PostgreSQL 16 per C5 §5.3, `region`, `settings.availability_type` from the variable, `settings.tier`, `settings.ip_configuration` with `ipv4_enabled = false` and `private_network = var.network_self_link`, `backup_configuration` enabled with `point_in_time_recovery_enabled = var.enable_pitr`, `deletion_protection`, **`depends_on = [var.psa_connection_id]`-equivalent** (see below) |
| `google_sql_database` | `this` | `name = var.db_name`, instance reference |
| `random_password` | `app` | length ≥ 24, no characters that break a JDBC URL or a shell quote |
| `google_sql_user` | `app` | `name = var.db_user`, password from `random_password` |
| `google_secret_manager_secret` | `db_password` | `secret_id = var.secret_id`, automatic replication |
| `google_secret_manager_secret_version` | `db_password` | payload = the generated password |

**The PSA ordering.** `depends_on` cannot take a variable expression. Pass the connection id in and create a
`null_resource`-free ordering by either (a) referencing `var.psa_connection_id` inside a `locals` value that
the instance's `ip_configuration` consumes trivially, or (b) placing `depends_on = [module.network]` on the
two module blocks in `envs/dev/main.tf`. **Prefer (b)** — it is honest, applies to both instances, and is
what trap 1 asks for. Say which you used in the module README.

**One module, two instantiations.** [ADR-008 D2](../docs/adr/ADR-008-terraform-layout-state-and-provider-versions.md#adr8-decision)
says "separate modules, not one module twice", while [05 §5.4](../docs/05-infrastructure.md#gcp-tf) says
`cloudsql (×2)`. C5 §5.6 pins only the **block** names `sql_lock`/`sql_pay`, so a single module directory
instantiated twice satisfies the contract; the differing intent lives in the inputs and in the README, and
duplicating ~60 lines of HCL to express it would be worse. Record this as a knowing ADR-008 D2 deviation in §9.

**Per-instance inputs:** `sql_lock` → REGIONAL, PITR **on**, `deletion_protection` initially `true`;
`sql_pay` → ZONAL, PITR off, `deletion_protection` `true`. Both `tier` from `var.sql_tier` (root) so the cost
knob is in one place.

**Outputs.** Mark nothing sensitive as an output. The pods read the password from Secret Manager at runtime
via Workload Identity (T-053) and receive it as `LOCK_DB_PASSWORD` / `PAY_DB_PASSWORD`-style env vars per
C5 §5.7; Terraform's job ends at the secret version.

## 5. Acceptance criteria

1. `terraform -chdir=deploy/terraform/envs/dev fmt -check -recursive` exits 0 and `validate` reports valid.
2. `grep -rn 'availability_type' deploy/terraform/envs/dev/main.tf` shows `REGIONAL` for `sql_lock` and `ZONAL` for `sql_pay`.
3. `grep -rn 'ipv4_enabled' deploy/terraform/modules/cloudsql/main.tf` shows `false`; no `authorized_networks` block exists.
4. Both module blocks carry `depends_on` on the network module (or the documented alternative), verifiable by grep.
5. The four pinned names `lockdb`, `paydb`, `lockapp`, `payapp` and the two pinned secret ids appear exactly once each in `envs/dev/main.tf`.
6. No `output` block anywhere returns a password, and `grep -rn 'random_password' deploy/terraform/envs/dev` returns nothing.
7. `dev.auto.tfvars` still contains no credential.

## 6. Verification

`terraform -chdir=deploy/terraform/envs/dev init -backend=false && terraform -chdir=deploy/terraform/envs/dev validate`
— expected valid. `terraform -chdir=deploy/terraform/envs/dev fmt -check -recursive` — exit 0.
`grep -rn 'sensitive' deploy/terraform/modules/cloudsql/outputs.tf` — expected no hits, because no output is
secret-bearing at all. Post-apply checks (`gcloud sql instances describe dlock-pg-lock --format='value(settings.availabilityType)'`
returning `REGIONAL`, and `psql` over private IP) are recorded in the module README as the T-054+ verification, not run here.

## 7. Out of scope

Running Flyway, creating schemas or seeding accounts (owned by M1/M2 code and the T-05x deploy tasks).
IAM roles and Workload Identity — T-053. Read replicas, failover drills, the `dlock-pg-lock` failover
demonstration — M6/M7. Do not add a `google_sql_ssl_cert`: private IP is the posture.

## 8. Hazards

- **Trap 1** ([05 §5.6](../docs/05-infrastructure.md#gcp-traps)): without explicit ordering the instance creation fails with an unhelpful peering error, or succeeds unreachable. This is the single most likely failure of this task.
- **Trap 3:** `deletion_protection` defaults true; teardown will need a first `apply` that flips it. Expose it as a variable now so T-059 does not edit module source.
- A generated password containing `@`, `:`, `/` or a quote breaks JDBC URLs and shell-projected env vars — constrain the character set.
- Instance **names cannot be reused for ~7 days** after deletion. If a rename is ever needed, report rather than improvise a suffix that violates C5 §5.6.
- `random_password` in state is plaintext by design (ADR-008 D6) — the mitigation is the private versioned bucket, not cleverness here.
- No git commands ([ADR-011](../docs/adr/ADR-011-no-git-initialisation-yet.md)).

## 9. On completion

Mark T-052 done in `tasks/README.md`. Record the ADR-008 D2 deviation (one module, two instantiations) and
the chosen `tier`, and note that neither instance has been applied yet.
