# T-058 — Console walkthrough: the click path

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 (GCP infrastructure) · **Estimate** 30 min. If the stack on disk has grown past ~35
declared `google_*` resources, cover bootstrap + `network` + `cloudsql` + `gke` this session and leave
`artifacts` / `observability` / the Kubernetes objects to a stated follow-up — do not rush all of it.

**Preconditions** — T-050…T-057. You inherit a complete `deploy/terraform/` tree: root `envs/dev` composing
`network`, `sql_lock`, `sql_pay`, `gke`, `artifacts`, `observability`, plus the Kubernetes manifests and
the deploy runbook produced in M5. Nothing has necessarily been applied. `deploy/` may already exist.

**Goal** — Write `deploy/console-walkthrough.md`: for every resource the Terraform stack creates, the
equivalent Cloud Console click path and the equivalent `gcloud` command, in apply order.

## 1. Why this task exists

Terraform is the deliverable, but it hides what GCP actually is: a reader who has only ever run
`terraform apply` cannot debug a peering failure or explain Workload Identity in an interview. Mapping
each resource to its console page and its `gcloud` verb turns the stack into a teachable object and gives
the operator a by-hand path when Terraform is wedged. It is also the honest statement of a hazard: every
resource created down the click path is invisible to state and becomes a T-059 orphan.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Every resource name used in the doc (`dlock-vpc`, `dlock-pg-lock`, `dlock-gke`, `dlock-app`, `dlock-lock-db-password`, repo `dlock`) | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Project `dlock-lab`, region `europe-central2`, env-var spellings | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming), [§5.7](../docs/contracts/C5-config-build-and-naming.md#ct5-env) |
| The resource-by-resource inventory the doc must mirror, and the arguments that matter | [05 §5.4](../docs/05-infrastructure.md#gcp-tf) |
| Bootstrap-by-hand boundary (project, billing, API enablement, state bucket) | [05 §5.5](../docs/05-infrastructure.md#gcp-state) |
| The five failure modes each step must name | [05 §5.6](../docs/05-infrastructure.md#gcp-traps) |
| Kubernetes object inventory and the principal chain | [05 §5.7](../docs/05-infrastructure.md#gcp-k8s), [§5.9](../docs/05-infrastructure.md#gcp-wi) |
| `PodMonitoring` is a CRD, never a Terraform resource | [C4 §4.9](../docs/contracts/C4-observability.md#ct4-scrape) |
| Terraform 1.15 / google 7.x — console UI wording tracks the same generation | [C5 §5.3](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**, per
[04 §4.4](../docs/04-contracts.md#c-precedence).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/console-walkthrough.md` | New. The click path and `gcloud` equivalent, step for step, in apply order. |
| `deploy/README.md` | Modify: link the walkthrough, one line on when to use it (learning, and Terraform-wedged debugging only). |
| `tasks/README.md` | Modify: mark the T-058 row done. |

## 4. Specification

`deploy/console-walkthrough.md` opens with a **Read this first** block: this path is for *understanding
and for debugging*, never for building the deliverable; anything clicked here is outside state, will not
be destroyed by `terraform destroy`, and must be swept by hand ([T-059](T-059-teardown.md)).

Then one `##` section per Terraform module, in apply order — **bootstrap · network · sql_lock · sql_pay ·
gke · artifacts · observability · Kubernetes objects** — each carrying a table with exactly these columns:

| Column | Content |
|---|---|
| # | Step number, continuous across the whole document |
| Terraform | The `resource "<type>" "<local>"` as it appears in the module on disk |
| Console | Product → page → the named button/field, e.g. *VPC network → VPC networks → Create VPC network → Subnet creation mode: Custom* |
| `gcloud` | One command line with the pinned names filled in; `--project=dlock-lab --region=europe-central2` explicit, never implied by config |
| Verify | The read-only `gcloud … describe`/`list` that proves the step landed |
| If you skip it | The concrete downstream symptom |

Rules for the tables: **every** `google_*` resource declared anywhere under `deploy/terraform/` gets a row — read the
files, do not work from §5.4 alone. Where no `gcloud` verb exists (e.g. Workload Identity *pool* — it is
implicit in the cluster), write `n/a — implicit` and say why. Where the console cannot express the resource
faithfully, say so rather than inventing a click path.

Two short prose sections close the document:

1. **Where the console is genuinely better** — Logs Explorer, Metrics Explorer, the Cloud SQL query-insights
   and operations views, the billing report used as the T-059 zero-spend proof. These are read paths, so they
   create no drift.
2. **Drift** — the two-sentence rule: a console edit to a Terraform-managed resource is reverted by the next
   apply, silently; a console-*created* resource is never reverted and never destroyed. Point at
   `terraform plan` as the detector.

No HCL, no YAML, and no manifest bodies in this document — it is a mapping table, not a second copy of the stack.

## 5. Acceptance criteria

1. `deploy/console-walkthrough.md` exists and contains the eight named `##` sections in apply order.
2. Every `google_*` resource type-and-local-name declared under `deploy/terraform/` appears in exactly one row.
3. Every row has all six columns non-empty; `gcloud` cells are either a command line or `n/a — implicit` with a reason.
4. Every `gcloud` command line names `dlock-lab` and, where the resource is regional, `europe-central2`.
5. Each of the five [§5.6](../docs/05-infrastructure.md#gcp-traps) traps is referenced from the "If you skip it" cell of at least one row.
6. The Read-this-first block links `T-059-teardown.md`; the drift section names `terraform plan`.
7. `deploy/README.md` links the new file; no other file's content changes.
8. The document contains no HCL/YAML code fence.

## 6. Verification

```bash
grep -rhoE 'resource "google_[a-z_]+" "[a-z_]+"' deploy/terraform/ | sort -u  # inventory
grep -coE 'google_[a-z_]+" "' deploy/console-walkthrough.md              # rows: counts must match
grep -c 'dlock-lab' deploy/console-walkthrough.md                        # ≥ one per gcloud cell
grep -nE '^```(hcl|yaml|tf)' deploy/console-walkthrough.md               # expect no match
while read -r c; do gcloud $c --help >/dev/null 2>&1 || echo "BAD: $c"; done < <(grep -oE 'gcloud [a-z-]+ [a-z-]+ [a-z-]+' deploy/console-walkthrough.md | sed 's/^gcloud //' | sort -u)
```
Expected: the first two counts agree; the fence grep is empty; the loop prints no `BAD:` line (every
`gcloud` group/verb triple resolves). `gcloud --help` needs no credentials, so this runs offline.

## 7. Out of scope

Applying anything. Teardown order, the budget resource and the orphan sweep are **T-059**. The operator
runbook, image push and Flyway steps belong to the earlier M5 tasks that own them; link, do not restate.
Screenshots are M7 publication work.

## 8. Hazards

Console UI labels drift faster than any API — write the *navigation intent* ("Create instance → choose
PostgreSQL → Single zone") rather than transcribing a button colour or a beta tab name. The second trap is
this document quietly becoming a build path: a reader who clicks through it produces a stack Terraform
cannot destroy and cannot even see ([05 §5.12](../docs/05-infrastructure.md#gcp-teardown)), which is why
the warning is the first thing on the page, not an appendix.

## 9. On completion

Mark the T-058 row done in `tasks/README.md`. If any resource had no faithful console equivalent, list it
there in one line — that list is worth a paragraph in the M7 write-up.
