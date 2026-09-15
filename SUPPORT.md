# Support

Where to ask, depending on what you need.

| I want to… | Go to |
|---|---|
| Browse everything in one place | The [documentation site](https://rednavis.github.io/distributed-lock-lab/) — full-text search across the doc set |
| Understand what this project is | [`README.md`](README.md), then [`docs/00-charter.md`](docs/00-charter.md) |
| Find something to work on | [`CONTRIBUTING.md` §3](CONTRIBUTING.md#3-finding-work-you-can-actually-start) and the [task board](tasks/README.md) |
| Look up a name, schema, or signature | [`docs/04-contracts.md`](docs/04-contracts.md) — the index to every pinned identifier |
| Understand why something was designed this way | [`docs/adr/`](docs/adr/) — fourteen decisions with their reasoning |
| Ask a question | [GitHub Discussions](https://github.com/rednavis/distributed-lock-lab/discussions) |
| Report a bug | [Bug report issue](https://github.com/rednavis/distributed-lock-lab/issues/new?template=bug.yml) |
| Report a security vulnerability | **[`SECURITY.md`](SECURITY.md) — privately, not a public issue** |
| Challenge a pinned name | [Contract change issue](https://github.com/rednavis/distributed-lock-lab/issues/new?template=contract-change.yml) |
| Propose scope not currently planned | Discussions first — check [non-goals](docs/00-charter.md#ch-nongoals), the answer may already be "deliberately not" |

## Response expectations

This is a volunteer project with one lead maintainer. Honest targets rather than guarantees:

| | |
|---|---|
| Discussions and questions | A few days, usually |
| Bug reports | Triaged within a week |
| Pull request review | Five working days ([`GOVERNANCE.md`](GOVERNANCE.md#review)) |
| Security reports | Acknowledged within five working days ([`SECURITY.md`](SECURITY.md)) |

If something has gone quiet past these, a polite nudge on the thread is welcome and will not annoy
anyone. Silence here means somebody is busy, not that your question was unwelcome.

## Asking a good question

No question about this codebase is too basic. The ones that get answered fastest tend to include:

- **What you were trying to do**, and which task or document you were working from.
- **What you expected**, and what happened instead.
- **The actual output** — the error, the failing test, the terminal text. Pasted, not summarised.
- **Your environment** if it might matter: JDK version, Docker version, operating system.

If you are stuck on an approach rather than an error, say so directly. "I think `T-024` wants X but the
contract reads like Y — which is it?" is a perfectly good question, and it is much cheaper to answer
before you have written the code than after.

## What this project cannot help you with

- **Production deployment.** This software is not production-ready and
  [`SECURITY.md`](SECURITY.md) explains exactly why. Questions about running it in front of real money
  will get a recommendation not to.
- **Your unrelated distributed-lock problem.** We are happy to point you at the reasoning in
  [`docs/03-architecture.md`](docs/03-architecture.md) and [`docs/adr/`](docs/adr/), but this is not a
  consulting channel.
- **Cloud bills.** Milestones M5 and M6 cost real money and the documentation says so repeatedly and in
  advance. Read [`docs/05-infrastructure.md`](docs/05-infrastructure.md#gcp-cost) first.

## A note on the first question you might have

> *There is no code. What do I contribute to?*

The specifications. There are 63 of them, each naming its deliverable files, acceptance criteria, and
verification commands. The design phase is complete and reviewable; the implementation phase is open
and almost entirely unclaimed. [`CONTRIBUTING.md`](CONTRIBUTING.md) is the entry point, and
[`docs/12-parallelization-map.md`](docs/12-parallelization-map.md) shows which tasks can start right
now.
