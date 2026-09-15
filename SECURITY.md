# Security Policy

## This software must not be deployed in front of real money

Before anything else, the most important security fact about this repository:

**distributed-lock-lab is a reference implementation and a teaching artifact. It is not
production-ready.** It has, by deliberate design and as documented in
[`docs/00-charter.md`](docs/00-charter.md#ch-nongoals):

- **No authentication and no authorisation on the lock API.** Any caller that can reach `lock-server`
  can acquire, renew, release, or force-revoke any lock. This single absence disqualifies it from
  production on its own.
- **No multi-tenancy and no isolation** between callers.
- **No quotas or rate limiting.** A single misbehaving client can exhaust the service.
- **Network isolation as the only trust boundary.** There is no mTLS and no service-to-service
  identity; `X-Fencing-Token` is a correctness mechanism, **not a credential**, and must never be
  treated as one.
- **No data retention, redaction, or PII handling story.** The domain is fictional and the fixtures are
  synthetic.

These are tracked as [assumption A-13 and the security posture table](docs/03-architecture.md#arch-security),
which also lists what a production version would have to add.

If you are evaluating this code for real use: the honest answer is that the correctness ideas here —
fencing tokens, conservative client-side expiry, enforcement in a process the lock service does not
control — are worth taking. The implementation is not.

## Supported versions

The project is pre-release. There are no supported versions yet, and no security backports.

| Version | Supported |
|---|---|
| `master` | Fixes land here |
| Everything else | — |

This table will be replaced when the project makes its first tagged release.

## Reporting a vulnerability

**Please do not open a public issue for a security vulnerability.**

Use GitHub's private vulnerability reporting:
**[Report a vulnerability](https://github.com/rednavis/distributed-lock-lab/security/advisories/new)**
(Security tab → Report a vulnerability).

If that is unavailable to you, contact the lead maintainer
[@AlexOreshkevich](https://github.com/AlexOreshkevich) directly and ask for a private channel.

**What to expect:**

| | |
|---|---|
| Acknowledgement | Within 5 working days |
| Initial assessment | Within 10 working days |
| Disclosure | Coordinated with you; we will not publish before you are ready, and we will not sit on it indefinitely either |
| Credit | You are credited in the advisory unless you prefer otherwise |

This is a volunteer project with one lead maintainer. These are honest targets rather than a
contractual SLA, and if a deadline slips you will be told, not ignored.

**Helpful reports include:** the affected component, the version or commit, reproduction steps, and
what an attacker gains. A proof of concept is welcome but not required.

## What is in scope

In scope, and genuinely wanted:

- **Correctness flaws in the fencing mechanism.** A way for a stale holder's write to be accepted at
  either enforcement point is the most valuable bug you could find here, because it falsifies the
  project's central claim. This is true even though the service is not production-ready.
- Token monotonicity violations — any path where a fencing token can repeat or decrease.
- Flaws in the lease or session lifecycle that permit two simultaneous holders of one key.
- Supply-chain problems: a compromised or typosquatted dependency in the version catalog, a CI workflow
  that can be made to execute untrusted input with elevated permissions.
- Credentials, real data, or secrets committed anywhere in the repository or its history.
- Terraform or Kubernetes configurations that would expose a user's own infrastructure — an overly
  permissive IAM binding, a publicly reachable database, a missing `deletion_protection`.

Out of scope, because they are documented absences rather than defects:

- The missing authentication and authorisation on the lock API.
- The absence of rate limiting, quotas, or multi-tenancy.
- `rail-stub` behaving badly. It is **deliberately** non-idempotent and injects failures; that is its
  entire purpose.
- The two fencing kill switches (`payment.fencing.enabled`, `rail.proxy.fencing.enabled`) existing.
  They default to safe, log at `WARN` when disabled, and exist only so that the experiment can
  demonstrate corruption. A report that "disabling fencing causes duplicate payments" is describing the
  intended demonstration.
- Anything requiring an attacker to already have cluster or database administrator access.

## Secrets, data, and credentials

A standing rule, enforced in review and in CI:

- **No real payment data, personal data, or credentials in this repository, ever.** Synthetic fixtures
  only.
- No secrets in source, in test fixtures, in Kubernetes manifests, or in committed Terraform state.
  Credentials are projected from a secret manager at runtime
  ([C5 §5.7](docs/contracts/C5-config-build-and-naming.md#ct5-env)).
- No real organisation, customer, partner, or product names. The domain is fictional and stays that
  way.

If you find any of the above already committed, **report it privately** rather than opening a public
issue — a public issue is a pointer to the secret.

## Running this project costs money

Milestones M5 and M6 provision real cloud infrastructure. A budget alert is created **before** the first
`terraform apply`, deliberately, and the teardown procedure is a task of its own
([`T-059`](tasks/T-059-teardown.md)). Read
[`docs/05-infrastructure.md`](docs/05-infrastructure.md#gcp-cost) before running anything that bills,
and use a dedicated project you are willing to delete. Deleting the project is the only teardown
guaranteed to be complete.

An unexpected bill is not a security vulnerability, but it is a real harm this project can cause you,
so it is documented in the same breath.
