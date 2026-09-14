# ADR-014 — Apache-2.0 with DCO sign-off {#adr14}

**Status:** Accepted, 2026-09-14
**Related:** [ADR-012](ADR-012-git-and-public-publication.md), [`LICENSE`](../../LICENSE), [`NOTICE`](../../NOTICE)
**Deciders:** lead maintainer
**Requirements touched:** —
**Contracts touched:** [C5 §5.3](../contracts/C5-config-build-and-naming.md#ct5-catalog) (dependency licence compatibility)

## A14.1 Context {#adr14-context}

Publishing this repository ([ADR-012](ADR-012-git-and-public-publication.md)) requires answering two
questions that are easy to get wrong once and expensive to change later: **under what licence is the
code offered**, and **on what terms are contributions accepted**.

Both are effectively irreversible. Relicensing requires the agreement of every contributor whose code
remains in the tree, and a project that changes its contribution terms after the fact has to re-obtain
agreement from everyone who already contributed.

Three forces apply here specifically.

**This repository teaches a technique.** Its product is evidence and an argument. The most likely
downstream use is somebody lifting the fencing pattern — the persisted high-water mark, the conditional
`UPDATE`, the conservative client deadline — into their own system. That use should be unambiguously
permitted, including commercially, or the project fails at its actual purpose.

**The peer group matters.** The infrastructure this project models and depends on — etcd, Kubernetes,
Terraform, Spring Boot, Micrometer, OpenTelemetry — is overwhelmingly Apache-2.0. A licence that
creates friction with that ecosystem creates friction for exactly the audience the repository is for.

**The contributor barrier matters more than usual.** This project wants first-time open-source
contributors, and a signature-collecting CLA is a well-documented deterrent that stops people at the
door for reasons unrelated to their code.

## A14.2 Decision {#adr14-decision}

**We license the project under Apache-2.0 and accept contributions under the Developer Certificate of
Origin, because the patent grant matters for a repository whose purpose is that people copy the
technique, and because inbound-equals-outbound removes the need for a CLA entirely.**

| Part | Decision |
|---|---|
| Licence | Apache License 2.0, verbatim in [`LICENSE`](../../LICENSE) |
| Attribution | [`NOTICE`](../../NOTICE), carrying the fictional-domain and no-real-data statements |
| Source headers | `SPDX-License-Identifier: Apache-2.0`, enforced by the Spotless `licenseHeader` step |
| Contribution terms | [DCO](https://developercertificate.org/) sign-off — `git commit -s`. **No CLA** |
| Enforcement | `.github/workflows/dco.yml` fails a pull request with any unsigned commit |
| Inbound = outbound | Apache-2.0 §5: contributions are licensed under the same terms unless stated otherwise. This is why no separate agreement is needed |
| AI disclosure | A `Co-Authored-By` trailer naming the tool, in addition to the human's sign-off ([`AGENTS.md`](../../AGENTS.md)) |

**On the patent grant specifically.** MIT's silence on patents is usually harmless, and would probably
be harmless here. But this repository exists to be copied into production systems that move money, and
Apache-2.0 §3 gives every downstream user an explicit patent licence from every contributor, plus a
defensive termination clause. For a project whose entire success condition is "somebody adopted this
technique", removing a category of legal uncertainty is worth the extra 190 lines of licence text.

**On the DCO specifically.** The DCO asks the contributor to assert that they wrote the patch or
otherwise have the right to submit it. It is a statement in the commit message, not a document to sign
and return, and it is checkable by CI. It gives a project of this size essentially all of the practical
protection a CLA would, at a fraction of the friction — which is why the Linux kernel, GitLab, Chef and
many CNCF projects use it.

## A14.3 Consequences {#adr14-consequences}

| | |
|---|---|
| **Positive** | Downstream adoption, including commercial, is unambiguous and patent-safe — which is the point. Licence compatibility with the entire dependency set is automatic. Contributors need no paperwork; `git commit -s` is the whole ceremony. The `NOTICE` file gives the fictional-domain and no-real-data statements a permanent, legally conventional home that travels with any redistribution |
| **Negative** | Apache-2.0 is ~200 lines against MIT's ~20, and the `NOTICE` requirement is a real obligation on redistributors that MIT does not impose. The DCO adds a step contributors forget — expect a steady trickle of pull requests failing the check, which is why [`CONTRIBUTING.md`](../../CONTRIBUTING.md#branches-and-commits) gives the exact `git rebase --signoff` fix. SPDX headers must be maintained on every source file |
| **What we accept** | **The DCO is an assertion, not a verification.** We cannot confirm that a contributor actually holds the rights they claim, and neither can a CLA — it only changes who is left holding the problem. This is bounded by what the project is: no proprietary code is solicited, contributions are small and reviewed, the provenance of every dependency is pinned in one version catalog, and AI-assisted contributions must be disclosed so that reviewers can calibrate. If a genuine provenance dispute ever arises, the remedy is removal and rewrite, and the monorepo's single history makes identifying the affected commits straightforward |

## A14.4 Alternatives considered {#adr14-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| **MIT** | Shortest, most permissive, universally understood; no `NOTICE` obligation on redistributors | Silent on patents. For a repository whose success condition is adoption into money-moving systems, that silence is the one ambiguity worth paying to remove |
| **Apache-2.0 with a CLA** | Maximum protection; permits relicensing later without re-contacting contributors | Documented barrier to first-time contributors, and the ability to relicense unilaterally is a feature this project does not want. It also implies an entity to hold the agreements, which does not exist |
| **AGPL-3.0 or GPL-3.0** | Copyleft keeps improvements public | Actively counterproductive. The intended use is lifting a pattern into a proprietary payment system; a copyleft licence makes exactly that use legally fraught, and the peer ecosystem is permissive throughout |
| **CC BY-SA**, treating this as documentation | The product genuinely is partly "evidence and an argument" | Creative Commons licences are not designed for source code and are explicitly discouraged for it. The repository will contain nine Java modules |
| **No licence** | Requires no decision | Means "all rights reserved". Nobody may legally use it, which defeats publication entirely, and it is among the most common mistakes in new public repositories |

## A14.5 Revisit when {#adr14-revisit}

| Trigger | Threshold | Then |
|---|---|---|
| A dependency arrives with an incompatible licence | Any occurrence | Reject the dependency. Do not relicense the project around it; the version catalog is the chokepoint where this is caught |
| A contributor cannot give DCO sign-off for employer reasons | 2 occurrences | Open an ADR on a Corporate CLA **in addition to** the DCO, never replacing it |
| A downstream user reports the `NOTICE` obligation blocking adoption | Any occurrence | Clarify `NOTICE` rather than relicensing — the file is currently longer than it strictly needs to be |
| The project is donated to a foundation | If it happens | The foundation's IP policy supersedes this ADR; requires contributor agreement, so start it early |
