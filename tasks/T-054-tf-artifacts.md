# T-054 — Terraform module: Artifact Registry, and image build

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 · **Estimate** 30 min (module + push script; the shared Dockerfile already exists from
T-005 and is reused unchanged, not rewritten; the six pushes are I/O and may run past the session — they
are idempotent and restartable).

**Preconditions** — T-001, T-003 (every service module produces a Spring Boot `bootJar`), **T-005**
(`deploy/images/Dockerfile` — the repo's one `ARG MODULE` multi-stage build, already exercised locally by
`deploy/compose/compose.yaml --profile apps`), T-050
(`deploy/terraform` root + `envs/dev`, GCS backend), T-053 (`module "gke"` declares the `dlock-app`
Google service account). You inherit an `envs/dev` composing `network`, `sql_lock`, `sql_pay`, `gke`.
No image repository and no container image exists yet.

**Goal** — Add the `artifacts` module (one Docker repository, repo-scoped reader binding) and the
operator path that builds and pushes an immutably tagged image for all six deployable services.

## 1. Why this task exists

T-055 and T-056 write manifests referencing image URLs, so the registry is the last infrastructure that
precedes Kubernetes. It is also where the "no downloaded SA key" posture is kept or quietly broken:
`gcloud auth configure-docker` plus Workload Identity make a key file unnecessary ([05
§5.9](../docs/05-infrastructure.md#gcp-wi), NFR-12/13), and one shared Dockerfile stops six services
drifting into six subtly different base images.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Repo `dlock`; path `dlock/<module>`; tag `<module>:<short-sha>`; `:latest` never deployed; project `dlock-lab`, region `europe-central2` | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Module `artifacts`, local name `this`, inputs `region`/`repo_id`, output `repo_url`; `format = DOCKER`, cleanup policy on untagged | [05 §5.4](../docs/05-infrastructure.md#gcp-tf) |
| `roles/artifactregistry.reader` on the repo, nothing broader, no key file | [05 §5.9](../docs/05-infrastructure.md#gcp-wi) |
| Terraform 1.15 / google 7.x; Java 25 | [C5 §5.3](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |
| Actuator reachable in the image on port 8080 | [C4 §4.9](../docs/contracts/C4-observability.md#ct4-scrape) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**
([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/artifacts/{main,variables,outputs}.tf` | `google_artifact_registry_repository "this"` + reader binding; vars `project_id`, `region`, `repo_id`, `reader_member`; output `repo_url` |
| `deploy/terraform/envs/dev/main.tf`, `outputs.tf` *(modify)* | `module "artifacts"`, reader member from the `gke` SA email, `repo_url` re-exported |
| `deploy/images/Dockerfile`, `.dockerignore` *(inherited from T-005 — verify, do not rewrite)* | the one multi-stage `ARG MODULE` build serving all six services; touch it only if a real gap appears (e.g. a missing `EXPOSE 8080`), and record the change as a T-005 amendment |
| `deploy/images/build-and-push.sh`, `README.md` | builds + pushes six images printing name→digest; operator runbook (auth, tag rule, re-tagging) |

## 4. Specification

**Repository.** `repository_id = dlock`, `location` from `region`, `format = DOCKER`, and a cleanup policy
deleting untagged images older than 7 days while keeping all tagged ones. Set
`cleanup_policy_dry_run = false` and comment why: dry-run grows forever while reading as if it worked.

**IAM.** `roles/artifactregistry.reader` **on the repository resource**, member passed in as
`reader_member` in `serviceAccount:…` form. Do not create the service account — T-053 owns it. No writer
or admin role anywhere: pushes use the operator's own credentials, never the workload identity.

**Dockerfile — reused, not authored here.** T-005 delivered `deploy/images/Dockerfile`; this task points the
push script at it. Do not add a second image definition, per-module or otherwise. Confirm against the shape
T-005 pinned, which is stage one on a JDK 25 image: copy the wrapper, `settings.gradle.kts`,
`gradle/libs.versions.toml`, `build-logic/` and sources, run `bootJar` for `$MODULE`, extract the layered
jar. Stage two on a JRE 25 base: non-root user, layers copied dependency-first (dependencies,
spring-boot-loader, snapshot-dependencies, application) so a code-only change reuses cache, `EXPOSE 8080`,
and **no shell wrapper around the entrypoint** — a wrapper swallows `SIGTERM` and turns every eviction
into a 30-second kill (T-056 depends on this). No `HEALTHCHECK`; Kubernetes probes own that. The script
iterates six modules — `lock-server`, `payment-resource`, `payout-executor`, `rail-proxy`, `rail-stub`,
`harness`; `lock-api` and `lock-client` are libraries and get no image.

**The tag.** The contract pins `<module>:<short-sha>`. Take the sha from `git rev-parse --short HEAD`,
falling back to a `BUILD_ID` environment variable (7 lowercase hex characters) when building outside a
working tree — CI supplies it from `github.sha`. **Fail loudly if neither is available or the value is
malformed**, rather than defaulting to `latest`. `:latest` is never deployed
([C5 `#ct5-naming`](../docs/contracts/C5-config-build-and-naming.md#ct5-naming)): it makes a rollback
unidentifiable, which is exactly when you need to identify what is running.

**The script.** `set -euo pipefail`. Assert `gcloud config get project` is `dlock-lab`; read `repo_url`
via `terraform -chdir=… output -raw`; detect a missing docker credential helper by checking
`~/.docker/config.json` for the registry host rather than failing at push time; build with
`docker buildx build --platform linux/amd64` (an arm64 Mac otherwise produces images Autopilot cannot
run — it surfaces as `exec format error` in a crash-loop); push; print digests.

## 5. Acceptance criteria

1. `terraform fmt -check -recursive deploy/terraform` exits 0; `validate` in `envs/dev` exits 0 with
   `artifacts` in the module graph.
2. `grep -rn 'artifactregistry.writer\|artifactregistry.admin' deploy/terraform` returns nothing.
3. `grep -rn ':latest' deploy/` returns nothing outside prose in `README.md`.
4. Running the script with `BUILD_ID` unset exits non-zero and names the variable.
5. The Dockerfile has exactly one `ARG MODULE` and no module name in any `COPY`/`RUN`.
6. Six repositories exist under `dlock/`, each with one tag and a `linux/amd64` manifest.
7. A built image run locally serves `/actuator/prometheus` on 8080.

## 6. Verification

```
terraform -chdir=deploy/terraform/envs/dev init -upgrade && terraform -chdir=deploy/terraform/envs/dev apply
gcloud auth configure-docker europe-central2-docker.pkg.dev
BUILD_ID=a1b2c3d ./deploy/images/build-and-push.sh
gcloud artifacts docker images list europe-central2-docker.pkg.dev/dlock-lab/dlock --include-tags
docker run --rm -p 8080:8080 europe-central2-docker.pkg.dev/dlock-lab/dlock/lock-server:a1b2c3d
curl -s localhost:8080/actuator/prometheus | head -5
```

Expected: apply adds 2 resources; the list shows six repositories, one tag each; `images describe`
reports `linux/amd64`; the curl returns Prometheus text, not 404.

## 7. Out of scope

Manifests and `image:` references (T-055), etcd (T-056), KSA annotations and secret projection (T-057),
a CI publish job on the workflows from T-006/T-007, Binary Authorization, scanning, `linux/arm64`.

## 8. Hazards

- **Autopilot pulls with the node identity, not the KSA.** Reader on `dlock-app` does not authorise the
  kubelet's pull; same-project pulls work via the default compute SA. A `403 ImagePullBackOff` in T-055
  is fixed by a binding for that SA, not for `dlock-app`.
- **[05 §5.4](../docs/05-infrastructure.md#gcp-tf) lists `google_project_iam_member` here while
  [§5.9](../docs/05-infrastructure.md#gcp-wi) scopes the role to the repo.** Implement the repo-scoped
  binding (least privilege wins) and report the discrepancy — do not silently pick.
- Never copy the host `~/.gradle` into a layer (credential leak); the registry host is regional —
  `europe-central2-docker.pkg.dev`, not `gcr.io`.

## 9. On completion

Mark the T-054 row done in `tasks/README.md`. Note the `BUILD_ID`-for-short-sha substitution and which
IAM scope you implemented, with the reason.
