# Maintainers

The people who can approve and merge changes, and what each of them owns. How this list is joined and
left is [`GOVERNANCE.md` §4](GOVERNANCE.md#4-becoming-a-maintainer).

## Active

| Maintainer | Role | Areas |
|---|---|---|
| [@AlexOreshkevich](https://github.com/AlexOreshkevich) | Lead maintainer | Everything, currently. Design authority for the contracts and the decision records |

That is the whole list, and it is meant to grow. The project is deliberately structured — independent
task specifications, authoritative contracts, a published dependency graph — so that people can
contribute meaningfully without the lead maintainer in the loop on every decision.

## Areas looking for an owner

These are the natural first ownership areas. Landing a few contributions in one of them is the shortest
path to being asked to maintain it.

| Area | What it covers | Entry points |
|---|---|---|
| **Lock backends** | `lock-server/store.pg`, `lock-server/store.etcd`, backend parity | [`T-011`](tasks/T-011-pg-tryacquire.md), [`T-030`](tasks/T-030-etcd-tryacquire.md) |
| **Correctness harness** | Deterministic simulation, linearizability, fault injection | [`T-043`](tasks/T-043-sim-test.md), [`T-044`](tasks/T-044-linearizability.md) |
| **Infrastructure** | Terraform, Kubernetes, GKE Autopilot | [`T-050`](tasks/T-050-tf-root.md), [`T-051`](tasks/T-051-tf-network.md) |
| **Observability and SRE** | Metrics, SLOs, alerts, runbooks, game day | [`T-060`](tasks/T-060-metrics.md), [`T-068`](tasks/T-068-runbook.md) |
| **Documentation** | The doc set, the glossary, diagrams, the write-ups | [`T-073`](tasks/T-073-fencing-writeup.md), [`T-074`](tasks/T-074-readme-final.md) |

Ownership of an area means reviewing pull requests that touch it and keeping its documentation true. It
does not mean writing all of it yourself.

## Emeritus

Nobody yet. When someone steps back they are listed here with thanks, and returning is a matter of
asking.

---

**Review routing** is configured in [`.github/CODEOWNERS`](.github/CODEOWNERS) and should be updated in
the same pull request that changes this file.
