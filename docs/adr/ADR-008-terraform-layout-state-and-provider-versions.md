# ADR-008 — Terraform layout, remote state, and pinned provider versions {#adr8}

| Field | Value |
|---|---|
| **Status** | **Accepted**, 2026-08-21 |
| **Decider** | Project owner / architect |
| **Scope** | `deploy/terraform/` only; no application code |
| **Contracts** | [C5 §5.3](../contracts/C5-config-build-and-naming.md#ct5-catalog) (Terraform 1.15, google 7.x) · [C5 §5.5](../contracts/C5-config-build-and-naming.md#ct5-layout) (`deploy/terraform`, state in `gs://dlock-tfstate`) · [C5 §5.6](../contracts/C5-config-build-and-naming.md#ct5-naming) (module + resource local names) · [C5 §5.7](../contracts/C5-config-build-and-naming.md#ct5-env) |
| **Requirements** | [SC-07](../00-charter.md#ch-success) (stands up from `terraform apply`, tears down to zero billable, budget alert before first apply) · [NFR-15](../01-requirements.md#br-nfr) (local no-cloud path) |
| **Related** | [ADR-009](ADR-009-managed-prometheus-and-slos-in-terraform.md) (SLOs as code), [ADR-010](ADR-010-monorepo-single-gradle-build.md) (one repo), [ADR-011](ADR-011-no-git-initialisation-yet.md) (no remote yet) |

## 8.1 Context {#adr8-context}

One GCP project, `dlock-lab`, one region, `europe-central2`, and a topology that is the *subject* of the
project rather than incidental to it: two Cloud SQL instances on purpose — `dlock-pg-lock` **REGIONAL** so
synchronous-standby failover can be demonstrated, `dlock-pg-pay` **ZONAL** for cost — plus a GKE Autopilot
cluster, a VPC with secondary ranges, Workload-Identity service accounts, and two separate database
password secrets. That topology encodes decisions, so it belongs in reviewable text, not in console clicks.

Three forces:

| Force | Implication |
|---|---|
| The environment is created and destroyed repeatedly (a project, not a fleet) | `destroy` must be as reliable as `apply`; anything created outside Terraform leaks cost. |
| The author works alone, from one machine, with no CI yet ([ADR-011](ADR-011-no-git-initialisation-yet.md)) | The realistic loss is not a concurrent-apply collision — it is a **lost or corrupted local state file**, which orphans real billable resources. |
| The source booklet's examples were written against **google provider 5.30** and older Terraform | Copying those pins would ship a project that is out of date on day one and would hide 6.x/7.x breaking changes rather than teach them. |

## 8.2 Decision {#adr8-decision}

| # | Rule |
|---|---|
| D1 | **Layout: reusable modules + thin environment roots.** `deploy/terraform/modules/<noun>/` holds the reusable units; `deploy/terraform/envs/dev/` is a thin root that wires them and holds the backend block and the tfvars. Only env roots are ever `apply`-ed; modules are never applied directly. |
| D2 | **One module per failure domain**, named as nouns in snake_case per [C5 §5.6](../contracts/C5-config-build-and-naming.md#ct5-naming): `network`, `sql_lock`, `sql_pay`, `gke`, `iam`, `secrets`, `registry`, `observability`, `budget`. `sql_lock` and `sql_pay` are **separate modules, not one module twice**, because their availability types and their reasons for existing differ. |
| D3 | **Remote state in GCS from the very first `apply`**, bucket `gs://dlock-tfstate`, **object versioning on, uniform bucket-level access, per-env `prefix`**. The bucket is created by a documented bootstrap command, not by the root that stores its state in it. |
| D4 | **Pin forward, never backward:** `required_version = "~> 1.15"`, `hashicorp/google ~> 7.0` (and `google-beta` at the same major when needed). Versions appear in `required_providers`, and the catalog row in [C5 §5.3](../contracts/C5-config-build-and-naming.md#ct5-catalog) is the single statement of intent. |
| D5 | **`.terraform.lock.hcl` is committed** once a repository exists, with all relevant platforms recorded. Pessimistic constraints choose the version; the lock file makes it reproducible. |
| D6 | **No secret values in tfvars or state-visible outputs.** Secret Manager holds the passwords; Terraform manages the secret *resources* and the IAM bindings, not the payloads. Passwords are set out of band and the state bucket is still treated as sensitive, because state always leaks more than you expect. |
| D7 | **One env for now: `dev`.** The directory split exists so a second env is additive, not so it is created speculatively. |
| D8 | The **budget alert module applies first** (SC-07) and a documented `terraform destroy` in `envs/dev` is the exit path; anything that survives destroy is a bug with a monthly bill. |

Bootstrap and daily operator commands (the only place these appear):

```
gcloud config set project dlock-lab
gcloud storage buckets create gs://dlock-tfstate --location=europe-central2 \
    --uniform-bucket-level-access
gcloud storage buckets update  gs://dlock-tfstate --versioning
terraform -chdir=deploy/terraform/envs/dev init
terraform -chdir=deploy/terraform/envs/dev plan  -out=tfplan
terraform -chdir=deploy/terraform/envs/dev apply tfplan
terraform -chdir=deploy/terraform/envs/dev destroy
```

*Why `-out=tfplan` always:* an `apply` that re-plans is an `apply` nobody reviewed. *Failure mode of
skipping it:* the plan you read and the plan that ran differ, and you find out from the bill.

## 8.3 Consequences {#adr8-consequences}

**Positive**

- State survives laptop loss, and versioning turns "I corrupted state" from an incident into an object-generation restore.
- Module-per-failure-domain means `sql_lock` can be failed over, tainted or replaced without touching the resource database — the same separation the runtime depends on.
- Pinning to current majors surfaces 7.x argument changes now, while the project is small, instead of during a later upgrade.
- Reviewable topology: the REGIONAL/ZONAL asymmetry is visible in code as an argument, so nobody "helpfully" makes both instances match.

**Negative**

- A bootstrap step exists outside Terraform (the state bucket), which is a small, permanent chicken-and-egg wart.
- Pinning forward means fewer copy-paste-ready examples on the internet match this repo; some 5.x-era snippets will need translating.
- Two SQL modules duplicate a few arguments that a single parameterised module would share.
- `~>` still allows minor upgrades on a fresh `init` without a lock file, so D5 is load-bearing rather than decorative.

**What we accept**

- No state locking beyond what the GCS backend provides, and no CI-run plans yet ([ADR-011](ADR-011-no-git-initialisation-yet.md)). With a single operator this is acceptable; with two it is not.
- Terraform state is treated as a sensitive artifact for good, not audited into cleanliness.

## 8.4 Alternatives considered {#adr8-alternatives}

| Alternative | Why rejected |
|---|---|
| **Local state** (`terraform.tfstate` on disk) | The single most likely way to orphan billable GCP resources in a project that is destroyed and rebuilt often. Cheap to avoid, expensive to regret. |
| Add remote state **later**, once it matters | The migration happens exactly when state is already valuable and already the only record of reality. Do it on run one, when the state is empty and the move is free. |
| **Terraform workspaces** instead of env directories | Workspaces hide which environment you are pointed at in invisible CLI context; a directory is visible in the prompt, in the command, and in the diff. |
| **One flat root module**, no modules | Works at ten resources, then every change re-plans everything and the REGIONAL/ZONAL distinction gets refactored away by accident. |
| A **module per resource** | Wrapper modules that add nothing but indirection; the boundary should be a failure domain, not a resource type. |
| Copy the booklet's **google 5.30** pins | Ships a knowingly stale project and teaches an obsolete argument surface. Move forward; never pin backwards ([C5 §5.3](../contracts/C5-config-build-and-naming.md#ct5-catalog)). |
| **Unpinned** providers (`>= x`) | A provider major bump then arrives via an unrelated `init` and rewrites the plan. Reproducibility beats novelty. |
| Terragrunt / a wrapper generator | Extra tool, extra vocabulary, and it obscures the plain-Terraform mechanics the project is meant to demonstrate. |
| Console-first ("click it, import later") | Import is not a plan; the topology's *reasons* never make it into the repo. |

## 8.5 Revisit when {#adr8-revisit}

| Trigger | Action |
|---|---|
| A second operator or CI gains apply rights | Split plan/apply identities, require plan-in-CI, and re-check state-bucket IAM; the single-operator assumption is gone. |
| A second environment is genuinely needed | Add `envs/<name>/`; do **not** retrofit workspaces. Re-verify no module hard-codes `dev`. |
| Terraform 1.16+ or google provider 8.x ships | Bump the catalog row, re-run `init -upgrade`, re-commit the lock file, read the upgrade guide before the plan — one major at a time. |
| `terraform destroy` leaves anything behind | Treat as a defect: find the out-of-band resource and bring it under Terraform or delete it from the runbook. |
| Secrets ever need to be *generated* by Terraform | Revisit D6 before, not after; that change makes state a credential store. |
| The project is ever run in a shared project | Stop and reconsider naming and IAM: every `dlock-*` name here assumes a dedicated project. |
