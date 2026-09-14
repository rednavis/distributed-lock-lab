# ADR-011 — Version control and publication are deliberately deferred {#adr11}

> [!IMPORTANT]
> **This decision has been superseded by
> [ADR-012 — Git version control and public publication](ADR-012-git-and-public-publication.md)
> (2026-09-14).**
>
> The repository is now under git and published publicly. **The rules below no longer apply** — in
> particular, git commands are expected rather than forbidden, and CI runs on every pull request.
>
> The original argument is retained below the line, because an ADR is immutable once accepted and
> because ADR-012's reasoning only makes sense against the argument it replaces. Only the status
> header and the repository's house terminology have been updated; no reasoning has been altered.
> The condition this
> ADR set for publication — a sanitisation review confirming the domain is fictional, no real
> organisation is named, and every number is a labelled assumption — **was performed**, and is now
> enforced continuously by the hygiene job in `.github/workflows/docs.yml` rather than as a one-time
> gate.
>
> For current practice see [`CONTRIBUTING.md`](../../CONTRIBUTING.md) and
> [ADR-013](ADR-013-parallel-contribution-model.md).

---

| Field | Value |
|---|---|
| **Status** | **Superseded by [ADR-012](ADR-012-git-and-public-publication.md)**, 2026-09-14 (originally Accepted, 2026-08-21) |
| **Decider** | Project owner (explicit standing instruction) |
| **Scope** | The whole working tree. No `git init`, no remote, no commits, no CI runs. |
| **Requirements** | [00 §0.8 D-01](../00-charter.md#ch-deferred) (no repository initialised; monorepo + trunk-based development are *future intent* only) · [00 §0.1](../00-charter.md#ch-what) (the constraints a review pass must confirm) |
| **Related** | [ADR-010](ADR-010-monorepo-single-gradle-build.md) (the repo structure this defers) · [ADR-008](ADR-008-terraform-layout-state-and-provider-versions.md) (lock file and plan-in-CI depend on it) · [ADR-009](ADR-009-managed-prometheus-and-slos-in-terraform.md) (SLOs-as-code assumes reviewable diffs) |

## 11.1 Context {#adr11-context}

This is a portfolio and study artifact written by one person on one machine. It will eventually be public,
which makes initialisation a **one-way door**: the first push publishes the history, and git history is not
a place where mistakes are quietly corrected. Two classes of content make that risk non-trivial —

| Risk | Why history makes it worse |
|---|---|
| Identifiable content — a real employer, partner, customer or product name slipping into prose that is meant to describe a **fictional mid-size payment service provider** | A later fix leaves the original in an earlier commit, reachable forever unless the history is rewritten. |
| A number that reads as production data | Every figure in this set must be an explicitly labelled ASSUMPTION; an unlabelled one, once committed, is a claim about a real system. |
| Credentials — a GCP key file, a database password, a `.tfvars` | Secret scanning finds it *after* it is public; rotation is the only real remedy. |

The owner's instruction is therefore explicit: **initialisation, the remote, and publication happen only on
the owner's own action, after a review pass.** Recording that here as a decision rather than leaving it as an
absence is the point — an undocumented absence looks like an oversight, and the next session "helpfully"
fixes it.

## 11.2 Decision {#adr11-decision}

| # | Rule |
|---|---|
| D1 | **No `git init`, no remote, no commit, no push** in this working tree until the owner performs §11.4 personally. |
| D2 | **No agent or automated session runs any `git` command here**, including read-only ones. The prohibition is absolute so there is no judgement call to get wrong. |
| D3 | Documents may freely *describe* the monorepo, trunk-based flow, branch protection and CI workflows as **intent**. They must not claim these exist. |
| D4 | `.github/workflows/` may be authored as files; it will not have executed. Anything that depends on a CI run is marked theoretical until §11.4 step 8. |
| D5 | Until then, the working tree is protected by **ordinary backups**, not by history. There is no bisect, no blame, no revert. |
| D6 | This ADR is the single place the deferral is recorded, and it supersedes the temptation of any future session to "just initialise it quickly". |

## 11.3 What the deferral costs {#adr11-cost}

Stated plainly, because a deferral with unnamed costs is denial:

| Cost | Detail |
|---|---|
| **No CI, so every CI-dependent part of the plan is theoretical** | `./gradlew build` + `spotlessCheck` on every change, Testcontainers integration runs on clean machines, `terraform plan` in CI as a review artifact ([ADR-008](ADR-008-terraform-layout-state-and-provider-versions.md) D4/D5), image build and `<module>:<short-sha>` publish to Artifact Registry, and required status checks. All authored, none exercised. Their first real run **will** find defects. |
| **No trunk-based workflow to practise** | [ADR-010](ADR-010-monorepo-single-gradle-build.md) D5/D6 — short-lived branches, atomic contract-plus-consumers commits, review-gated trunk — are the practice this project claims and currently cannot demonstrate. |
| **No atomic-change evidence** | The strongest argument for the monorepo (one commit changes contract and all consumers together) has no artifact behind it yet. |
| **No provenance for the deliverables** | The M7 benchmark and the pg-vs-etcd comparison lose "at which commit was this measured?", which is the sentence that makes a benchmark credible. |
| **No `.terraform.lock.hcl` committed** | Provider reproducibility currently rests on the pessimistic constraint alone. |
| **Single-point data loss** | Laptop loss loses the entire doc set. Backups, not history, are the mitigation; treat that as an accepted operational risk, not as safety. |
| **No secret scanning or push protection** | The controls that would catch a leaked credential do not exist yet, which raises the bar for the §11.4 step-1 review. |

**What we accept:** slower feedback and a missing practice demonstration, in exchange for keeping the
one-way door shut until a deliberate sanitisation pass has been done. The trade is sound *only if the review
actually happens*; skipping it converts this ADR from prudence into procrastination.

## 11.4 Reversal — the exact ordered steps {#adr11-reversal}

Run by the **owner**, in this order. Steps 1–3 gate everything else.

| # | Step | Command / artifact |
|---|---|---|
| 1 | **Sanitisation review** of every file: no real employer / partner / customer / product names; the PSP is fictional; every number carries an explicit ASSUMPTION label; no production latency, volume or incident figures. | Manual read plus a grep sweep for candidate names and for bare digits in result claims. |
| 2 | **Secret sweep** of the tree: no key files, no `*.tfvars` with credentials, no `.env`, no password literals, no state files. | `grep -ril -e password -e private_key -e BEGIN\ PRIVATE`; inspect every hit. |
| 3 | **Write `.gitignore` before the first `add`**: `build/`, `.gradle/`, `.terraform/`, `*.tfstate*`, `*.tfvars`, `.env*`, `*-key.json`, IDE dirs. | A first commit that includes state or build output is the mistake this step prevents. |
| 4 | **Initialise, review the staging set, then commit once.** | `git init -b main` · `git add -A` · `git status` (read it — this is the last cheap checkpoint) · `git commit -m "docs: charter, requirements, domain model, contracts C1-C5, ADRs"` |
| 5 | **Add build reproducibility artifacts** and commit: Gradle wrapper (with checksum verification) and, after one `terraform init`, `.terraform.lock.hcl` with all needed platforms. | |
| 6 | **Create the remote PRIVATE first.** | `gh repo create <name> --private --source=. --remote=origin --push` |
| 7 | **Turn on the guards while it is still private:** secret scanning + push protection, Dependabot/Renovate, then branch protection on `main` (linear history, required review, required status checks — added in step 8). | Repository settings. |
| 8 | **Land CI and let it fail honestly:** `build.yml` (`./gradlew build spotlessCheck`, Testcontainers), `terraform-plan.yml` (**plan only**, no apply), `image-publish.yml` (`<module>:<short-sha>`). Fix what the first real run finds; only then mark those checks required. | `.github/workflows/` |
| 9 | **GCP auth via Workload Identity Federation**, never a long-lived service-account key; the CI identity gets plan/read only. | |
| 10 | **Second sanitisation pass, then flip to public**: add LICENSE, README stating the project is fictional and non-production, re-run step 1 against the diff since step 4. | `gh repo edit --visibility public` |
| 11 | **Update the docs that describe intent as intent**: [00 §0.8 D-01](../00-charter.md#ch-deferred), [ADR-010](ADR-010-monorepo-single-gradle-build.md) D5, and set this ADR to *Superseded*, naming the commit that did it. | |

**Failure mode of doing this out of order:** initialising first and reviewing later means the review's only
remaining remedy is history rewriting, and by then the remote may already have copies. The order is the
control.

## 11.5 Alternatives considered {#adr11-alternatives}

| Alternative | Why rejected |
|---|---|
| **`git init` now, local only, no remote** | Genuinely tempting — history and revert with no publication risk — but it invites an early `gh repo create` before step 1, and a local history with an unsanitised first commit is exactly what step 3/4 exist to prevent. Rejected as a matter of the owner's standing instruction, not of technical merit. |
| **Init and push to a private remote immediately** | Gets CI running today. Also publishes an unreviewed history to a third party and starts the clock on a leak that scanning cannot retroactively fix. |
| **Public from the first commit** | Maximum accountability, zero recoverability. Not for a document set that has not had a sanitisation pass. |
| **Never version control it** | Loses CI, provenance, and the trunk-based practice this project claims to teach. The deferral is temporary by design. |
| Let an automated session initialise it | Removes the human review that is the whole control, and the owner's instruction is explicit. Hence D2's absolute prohibition. |

## 11.6 Revisit when {#adr11-revisit}

| Trigger | Action |
|---|---|
| The owner completes the §11.4 step-1 and step-2 review | Execute steps 3–8 in one sitting; do not leave the tree half-initialised. |
| Any implementation task's completion depends on a CI run | Stop and reverse this ADR first, or explicitly record the task as verified locally only. |
| A second contributor appears | Reversal becomes mandatory; there is no defensible way to collaborate on an uninitialised tree. |
| The doc set becomes large enough that laptop loss is unacceptable | Reversal (private remote) is the cheapest insurance available. |
| Publication is requested for a job application or portfolio review | Run §11.4 in full, including the step-10 second pass, before sharing any link. |
