# T-007 — CI: infra, container, codeql, dependabot, templates

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M0 Foundations · **Estimate** 30 min (at the limit; if the container job stalls on registry
auth, split the file into a follow-up **T-007b** and finish the other four deliverables first)

**Preconditions** — T-006 done. You inherit `.github/workflows/build.yml`, its conventions (pinned actions,
least-privilege `permissions`, `concurrency` with cancel-in-progress, `timeout-minutes`), and the module list
from `settings.gradle.kts`. `deploy/terraform/` exists as a directory but may hold no `.tf` files yet.

**Goal** — Add the four supporting workflows and the contributor templates, so infrastructure code, container
images, dependencies and static analysis are all governed before any of them has content worth breaking.

## 1. Why this task exists

Every one of these guards is cheap now and expensive to retrofit: `terraform fmt -check` added after fifty
resources exists means one giant reformat commit; SBOM and provenance added after images are deployed means
untraceable artifacts; Dependabot added late means a wall of majors. Setting them up while the tree is nearly
empty means each one is verifiably green today, and every later task inherits the guard rather than the debt.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Terraform 1.15, google provider 7.x — versions live only in the catalog / provider block | [C5 `#ct5-catalog`](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |
| Artifact Registry repo `dlock`; image path `dlock/<module>`; tag `<module>:<short-sha>`, never `:latest` | [C5 `#ct5-naming`](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Project `dlock-lab`, region `europe-central2` | [C5 `#ct5-naming`](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Terraform layout, state bucket, module naming | [ADR-008](../docs/adr/ADR-008-terraform-layout-state-and-provider-versions.md), [C5 `#ct5-layout`](../docs/contracts/C5-config-build-and-naming.md#ct5-layout) |
| No credential in any file | [C5 `#ct5-env`](../docs/contracts/C5-config-build-and-naming.md#ct5-env) |
| **Never** initialise or invoke git in a workflow step beyond the checkout action | [ADR-011](../docs/adr/ADR-011-no-git-initialisation-yet.md) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch.**

## 3. Deliverables

| Path | What |
|---|---|
| `.github/workflows/infra.yml` | `terraform fmt -check -recursive`, `init -backend=false`, `validate`, `tflint` |
| `.github/workflows/container.yml` | Per-module image build, push, SBOM, provenance |
| `.github/workflows/codeql.yml` | CodeQL for `java-kotlin`, on PR and a weekly schedule |
| `.github/dependabot.yml` | Four ecosystems (see §4) |
| `.github/pull_request_template.md` | The PR checklist |
| `.github/ISSUE_TEMPLATE/bug_report.md`, `.../design_question.md` | Two issue forms |

## 4. Specification

**infra.yml.** Triggers: PR and push touching `deploy/terraform/**` (path filter) plus `workflow_dispatch`.
One job, `terraform`, running in order: `fmt -check -recursive` (fails on any unformatted file),
`init -backend=false` (no state, no credentials — this is the whole reason validation works with zero GCP
access), `validate`, then `tflint`. Terraform version pinned to the catalog's, installed via the HashiCorp
setup action. It must exit 0 today with an empty or near-empty `deploy/terraform/` — if `validate` refuses on
an empty directory, add a minimal `versions.tf`-shaped file declaring only the required Terraform and provider
version constraints (that is configuration, not infrastructure, and M5 will build on it).

**container.yml.** Triggers: push on the default branch and `workflow_dispatch`; **not** on pull requests from
forks. A matrix over the five runnable modules (`lock-server`, `payment-resource`, `payout-executor`,
`rail-proxy`, `rail-stub`). Steps: build the image with Buildx; tag `<region>-docker.pkg.dev/dlock-lab/dlock/<module>:<short-sha>`
plus nothing else; generate an SBOM (SPDX or CycloneDX) and attach it as a run artifact; enable
SLSA provenance via Buildx attestation. Push is **conditional** on a repository variable/secret indicating
that Artifact Registry exists, and authenticates by Workload Identity Federation only — no service-account JSON
key, ever. Until M5 provisions the registry the job builds and skips the push; the workflow must still be green.
`permissions` needs `id-token: write` and `contents: read`, nothing more. All five legs use the **one** shared
`deploy/images/Dockerfile` from T-005 with `--build-arg MODULE=<module>`; skip a module whose service code does
not exist yet rather than failing the matrix leg.

**codeql.yml.** Language `java-kotlin`, autobuild replaced by the Gradle wrapper build (autobuild guesses wrong
on a convention-plugin monorepo). Triggers: PR, default-branch push, and a weekly `schedule`.
`permissions: security-events: write`, `contents: read`, `actions: read`.

**dependabot.yml.** Four ecosystems, weekly, grouped where a group is meaningful, with an explicit
`open-pull-requests-limit` so the project is never buried.

| Ecosystem | Directory | Note |
|---|---|---|
| `gradle` | `/` | picks up `gradle/libs.versions.toml` |
| `github-actions` | `/` | the pinned action majors |
| `docker` | `/deploy/compose` | the PostgreSQL and etcd images from T-005 |
| `terraform` | `/deploy/terraform` | provider constraints |

**Templates.** The PR template is a checklist, not prose: which contract anchors the change touches; whether
any contract changed (and if so, that `docs/04-contracts.md` §4.5 has a new row and dependent task specs were
revisited); Spotless run; tests added or a stated reason; docs/ADR updated. The bug form asks for the module,
the log `event` name, the fencing token seen, and whether `lock.fenced.out` or `rail.duplicate.attempted` was
non-zero. The design-question form asks which ADR the question challenges.

## 5. Acceptance criteria

1. All six files exist at the paths in §3 and parse as valid YAML/Markdown.
2. Every workflow declares least-privilege `permissions`, `timeout-minutes`, and `concurrency` with
   cancel-in-progress, matching T-006's conventions.
3. `infra.yml` runs `fmt -check -recursive` **before** `validate`, and uses `-backend=false`.
4. `terraform fmt -check -recursive deploy/terraform` and `terraform validate` both exit 0 locally.
5. `container.yml` contains no `:latest` tag and no service-account key reference; it declares
   `id-token: write`; its matrix names exactly the five runnable modules.
6. An SBOM step and a provenance/attestation setting are both present in `container.yml`.
7. `codeql.yml` uses the Gradle wrapper to build, not `autobuild`, and has a `schedule` trigger.
8. `dependabot.yml` declares exactly the four ecosystems and directories in the table, each with an interval
   and a PR limit.
9. No workflow step invokes `git` for anything other than the checkout action (ADR-011).

## 6. Verification

```
for f in .github/workflows/*.yml .github/dependabot.yml; do yq '.' "$f" >/dev/null || echo "BAD $f"; done
actionlint .github/workflows/                      # if installed; zero findings
terraform -chdir=deploy/terraform fmt -check -recursive
terraform -chdir=deploy/terraform init -backend=false && terraform -chdir=deploy/terraform validate
tflint --chdir=deploy/terraform                    # if installed
grep -rn ':latest\|credentials_json\|-----BEGIN' .github/   # expect no match
grep -rn 'permissions:' .github/workflows/ | wc -l          # expect >= 4
```

## 7. Out of scope

Writing any Terraform resources or module (M5, T-050 onward), writing or forking a Dockerfile (T-005 already
delivered the only one; this workflow consumes it), branch-protection settings (a repo-hosting concern, and there is no remote yet), and image
deployment or rollout (M5/M6).

## 8. Hazards

`terraform init` without `-backend=false` tries to reach `gs://dlock-tfstate` and fails with a credentials
error that looks like a workflow bug (ADR-008). CodeQL `autobuild` on this monorepo compiles a subset and
reports a falsely clean scan. Dependabot pointed at `/` for docker finds nothing, because compose lives in
`deploy/compose`. And a container job that pushes on pull requests leaks write access to fork contributors —
default-branch pushes only.

## 9. On completion

Mark the T-007 row done in `tasks/README.md`; note there whether the push step is currently skipped (it should
be, until T-05x creates the registry) and whether a placeholder Terraform version file had to be added.
