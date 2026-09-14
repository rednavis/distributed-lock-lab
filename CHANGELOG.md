# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project will use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from its first release.

**There is no release yet.** The project is in its specification-complete, pre-implementation phase —
see [`ROADMAP.md`](ROADMAP.md). The first tagged release will be `v0.1.0`, cut when milestone **M4**
closes and the fencing experiment ([`T-042`](tasks/T-042-fencing-demo.md)) is reproducible from a clean
clone. That is the first point at which there is anything worth versioning.

---

## [Unreleased]

### Added

- **Specification tree.** 13 design documents, five authoritative contracts (C1–C5), fourteen
  architecture decision records, and 63 implementation task specifications.
- **Open-source foundation.** Apache-2.0 licence with DCO sign-off
  ([ADR-014](docs/adr/ADR-014-apache-2-and-dco.md)), Contributor Covenant 2.1, and the full community
  health set: [`CONTRIBUTING.md`](CONTRIBUTING.md), [`GOVERNANCE.md`](GOVERNANCE.md),
  [`SECURITY.md`](SECURITY.md), [`SUPPORT.md`](SUPPORT.md), [`MAINTAINERS.md`](MAINTAINERS.md).
- **Contributor workflow.** Issue and pull-request templates, `CODEOWNERS` review routing, a label
  taxonomy, and GitHub milestone definitions mirroring the eight roadmap milestones.
- **CI.** Documentation link checking, repository hygiene and credential scanning, ledger consistency,
  DCO enforcement, and label synchronisation — all running today. The Gradle build, CodeQL and
  Terraform workflows are authored and self-skip until the code they check exists.
- [`docs/12-parallelization-map.md`](docs/12-parallelization-map.md) — the dependency graph showing
  which tasks can be worked simultaneously.
- [`docs/11-glossary.md`](docs/11-glossary.md) — every term of art used in the repository.
- [`AGENTS.md`](AGENTS.md) — obligations specific to AI contributors, including disclosure.

### Changed

- **Execution model: strictly sequential → parallel.** Task ids are now identifiers rather than a
  schedule, and any task whose preconditions are merged may be claimed
  ([ADR-013](docs/adr/ADR-013-parallel-contribution-model.md)).
- **Version control: none → git, published publicly**
  ([ADR-012](docs/adr/ADR-012-git-and-public-publication.md), superseding ADR-011).
- Documentation rewritten for an external audience, and renamed for clarity:
  `01-business-requirements` → `01-requirements`, `03-technical-architecture` → `03-architecture`,
  `05-gcp-architecture` → `05-infrastructure`, `07-correctness-and-test-strategy` →
  `07-correctness-and-testing`, `09-risk-register` → `09-risks`.

### Superseded

- [ADR-011](docs/adr/ADR-011-no-git-initialisation-yet.md) — "Version control and publication are
  deliberately deferred". Retained for the record; its reasoning is what
  [ADR-012](docs/adr/ADR-012-git-and-public-publication.md) argues against.

---

## Release plan

| Version | Cut when | Contains |
|---|---|---|
| `v0.1.0` | **M4 closes** | Both lock backends, both fencing enforcement points, the client SDK, and the `SIGSTOP` experiment reproducible from a clean clone |
| `v0.2.0` | M6 closes | Deployable infrastructure, SLOs, alerts, runbooks, and a completed game day |
| `v1.0.0` | M7 closes | The measured pg-vs-etcd comparison and the fencing write-up. **Still not production-ready** — see [`SECURITY.md`](SECURITY.md) |

`v1.0.0` means "the argument is complete and evidenced", not "safe to run in front of real money". That
distinction is permanent and is stated in the README, the charter and the security policy.
