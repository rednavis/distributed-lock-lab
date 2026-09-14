# T-008 — Repo front matter and non-goals

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M0 Foundations · **Estimate** 30 min

**Preconditions** — T-001…T-007 done. You inherit the full module skeleton, a green `./gradlew build`, the
local compose stack at `deploy/compose/`, and five workflows plus templates under `.github/`. The `docs/` set
and `docs/contracts/` are complete and are the authority for every claim the README makes.

**Goal** — Write the repository's public face so that a reader who spends ninety seconds on it learns what
fencing is and why this project exists, and a reader who spends ten minutes knows exactly what it deliberately does
not do.

## 1. Why this task exists

This is the artifact a hiring panel or a colleague actually opens, and the default README — badges, "Getting
started", `./gradlew build` — buries the only interesting thing in the repository under build instructions
nobody reads. The project's thesis is that a lock without a fencing token is not a lock, so the **first code block
must be the fencing demonstration**: the stale holder's write rejected because its token is lower. The non-goals
table exists for the same reason it exists in the charter — an unwritten boundary is how a project becomes an
unfinished platform (charter risk R6).

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Non-goals list, "never cut" list, success criteria | [charter `#ch-nongoals`](../docs/00-charter.md#ch-nongoals), [`#ch-success`](../docs/00-charter.md#ch-success) |
| Fictional customer framing — no real company, partner or product names | [charter `#ch-customer`](../docs/00-charter.md#ch-customer) |
| The fenced statement shape `UPDATE … WHERE fence < :token`; column is `fence` | [C1 `#ct1-fenced`](../docs/contracts/C1-database-schemas.md#ct1-fenced) |
| Rail high-water mark as the second fence point | [C3 `#ct3-railproxy`](../docs/contracts/C3-http-surfaces.md#ct3-railproxy), [ADR-007](../docs/adr/ADR-007-rail-fencing-proxy-for-a-non-cas-resource.md) |
| Module inventory and repo layout as printed in the README tree | [C5 `#ct5-modules`](../docs/contracts/C5-config-build-and-naming.md#ct5-modules), [`#ct5-layout`](../docs/contracts/C5-config-build-and-naming.md#ct5-layout) |
| Kill-switch names and what they demonstrate | [C5 `#ct5-killswitches`](../docs/contracts/C5-config-build-and-naming.md#ct5-killswitches) |
| Toolchain versions quoted from the catalog, never retyped from memory | [C5 `#ct5-catalog`](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |
| The repository is public and already carries a README, `CONTRIBUTING.md` and the full community health set — **reconcile with them, do not replace them** | [ADR-012](../docs/adr/ADR-012-git-and-public-publication.md), [`../README.md`](../README.md) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch.**

## 3. Deliverables

| Path | What |
|---|---|
| `README.md` | The front page, structured exactly as §4 orders it |
| `LICENSE` | Apache License 2.0, verbatim upstream text, current year, author name |
| `NOTICE` | Short attribution file Apache-2.0 expects alongside the licence |
| `SECURITY.md` | Reporting channel + the explicit "this is not production software" statement |
| `CONTRIBUTING.md` | How to build, the contract-precedence rule, the review expectations |

## 4. Specification

**README section order — load-bearing.**

| # | Section | Content |
|---|---|---|
| 1 | One-sentence what-and-why | Distributed lock service for a fictional mid-size payment service provider; the lesson is fencing |
| 2 | **The fencing demo — first code block in the file** | A short annotated transcript: holder A acquires (token N) → A pauses past its lease → B acquires (token N+1) and completes the payout → A wakes and its write is **rejected** by the fenced `UPDATE`, and its rail submission is **rejected** by the high-water mark. Show it as a terminal/psql-style transcript with the token values visible. Under 25 lines. |
| 3 | Why a lock at all | The bare balance update needs none — Postgres serialises one row; the lock is warranted only because the critical section spans a non-idempotent external side effect ([ADR-004](../docs/adr/ADR-004-payout-executor-as-the-protected-operation.md)) |
| 4 | The two fence points | Table: (a) paydb conditional `UPDATE`, (b) rail-proxy persisted per-account high-water mark — both enforced in processes **separate from the lock service** ([ADR-007](../docs/adr/ADR-007-rail-fencing-proxy-for-a-non-cas-resource.md)) |
| 5 | Architecture at a glance | ASCII diagram: executor → lock-server → (lockdb \| etcd); executor → payment-resource → paydb; executor → rail-proxy → rail-stub |
| 6 | Both backends are first class | pg `fencing_token_seq` vs etcd `ModRevision` **captured at grant time**; one SPI |
| 7 | Run it locally | Point at `deploy/compose/README.md` and the Gradle build; three commands maximum, placed **here**, not at the top |
| 8 | Repository map | The tree from `#ct5-layout`, one line of purpose per entry |
| 9 | Toolchain | Table quoting the catalog versions |
| 10 | **Non-goals** | The charter table, reproduced with its "why" column and links back to `#ch-nongoals`; add the "never cut at any schedule pressure" line |
| 11 | Status / honesty note | Which milestones are complete; explicitly that there is no authN on the lock API and that this alone disqualifies it from production |
| 12 | Docs index | Link the eleven `docs/` files and the five contracts |

**LICENSE / NOTICE.** Apache-2.0 unmodified; the copyright line names the author and the year. `NOTICE` states
the project name and that it contains no third-party code requiring attribution beyond declared dependencies.

**SECURITY.md.** Say plainly: a study project, deployed only to a throwaway GCP project, **no authentication on any
surface**, no real money, no PII, credentials only via Secret Manager or local `.env`. Give one reporting
channel (a GitHub issue is acceptable, since nothing here is confidential) and an explicit non-promise about
response times. Do not publish a private email address.

**CONTRIBUTING.md.** Build and format commands; the reading order for a fresh session (C5 → C1 → C2 → C3 → C4,
per `docs/04-contracts.md` §4.2); **the precedence rule** — contract beats task spec beats habit, and a
mismatch means stop and report, not fix locally; the contract change procedure (amend → §4.5 row → revisit
dependent task specs → implement); a note that Lombok is limited to `@RequiredArgsConstructor` and `@Slf4j`;
and the two-database rule as a review checkpoint.

## 5. Acceptance criteria

1. The first fenced code block in `README.md` is the fencing transcript — verifiable by finding the first
   ```` ``` ```` fence and reading it; it contains two distinct token values and the word "rejected".
2. No build or install command appears before the fencing demo section.
3. Both fence points are named, and the README states they live outside the lock service.
4. The non-goals section lists every row of `docs/00-charter.md` §0.5 — nine rows — plus the never-cut line.
5. `README.md` states that the lock API has no authentication and that this disqualifies it from production.
6. `LICENSE` is byte-identical to the upstream Apache-2.0 text apart from the copyright line; `NOTICE` exists.
7. `SECURITY.md` contains no private email address and no promised response SLA.
8. `CONTRIBUTING.md` reproduces the contract-precedence rule and the change procedure.
9. Every version number in the README matches `gradle/libs.versions.toml`; every relative link resolves.
10. No real company, partner or product name appears anywhere in the five files.

## 6. Verification

```
awk '/^```/{n++; if(n==1) f=1} f' README.md | head -30      # first block is the fencing transcript
grep -n 'gradlew' README.md | head -1                       # line number must exceed the demo block's
grep -c '^|' README.md                                      # tables present
grep -in 'authentication' README.md SECURITY.md
diff <(curl -sL https://www.apache.org/licenses/LICENSE-2.0.txt) LICENSE | head
grep -oE '\]\([^)#][^)]*\)' README.md | tr -d '])' | sed 's/^(//' | while read p; do [ -e "$p" ] || echo "DEAD $p"; done
grep -rniE '<real-employer-or-partner-names-you-must-not-use>' README.md   # substitute and expect no match
```

## 7. Out of scope

The benchmark numbers and the published write-up (M7, T-070 onward) — the status section says "not yet", it
does not preview results. Screenshots and dashboards (M6). A CHANGELOG (nothing is released). Any `git`
operation, including initialising the repo (ADR-011).

## 8. Hazards

The strong pull here is to write a conventional README; resisting it is the task. Second trap: inventing
numbers — no latency, throughput or success-rate figure may appear until T-070 measures one; write
`[unmeasured]`. Third: paraphrasing the fenced statement with a plausible synonym such as `fencing_token`
instead of the pinned column `fence` ([C1 `#ct1-fenced`](../docs/contracts/C1-database-schemas.md#ct1-fenced))
— the README is where such a synonym spreads fastest.

## 9. On completion

Mark the T-008 row done in `tasks/README.md` and record that M0 is closed, with the milestone exit check from
`docs/10-delivery-plan.md` (`./gradlew build` and `spotlessCheck` green in a clean tree, all modules present,
CI authored but not executed — see [ADR-011](../docs/adr/ADR-011-no-git-initialisation-yet.md), `lock-api`
dependency-free) confirmed or listed as outstanding.
