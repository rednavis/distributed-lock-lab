<!--
Thank you for contributing.

One task, one pull request. Open it as a draft early if you want feedback in progress.

Delete any section that genuinely does not apply — but do not delete the Verification
section. Pasted command output is what review is based on here.
-->

## What this changes

<!-- One or two sentences. What is different after this merges? -->

**Closes #**

**Task:** `T-0NN` — <!-- link the specification in tasks/, or write "not a task" -->

## Definition of done

<!-- All six must hold. Five out of six is not done. CONTRIBUTING.md §7. -->

- [ ] `./gradlew build` succeeds from a clean checkout
- [ ] `./gradlew spotlessCheck` is green, and no file outside this task's scope was reformatted
- [ ] The tests the specification names pass, and everything already green stayed green
- [ ] **The observability this task specifies actually emits** — scraped and observed, not merely called
- [ ] The ledger row in `tasks/README.md` is updated
- [ ] Deviations are recorded below, or there are none

## Verification

<!--
Paste the OUTPUT of the commands in your specification's §6. Actually paste it.
"Tests pass" is not evidence; the terminal output is.

If a verification step was impossible in your environment — no Docker, no cloud
credentials — say so explicitly here. "I could not verify gate 4, here is why" is
reviewable. Implying verification that did not happen is not.
-->

```console
$ ./gradlew :module:test --tests '*SomeIT'

```

**Gate 4 — observability emitted:**

<!--
If this task adds a metric: curl /actuator/prometheus and paste the matching lines.
If it adds a log event: trigger it and paste the JSON.
If the task adds neither, write "no observability in this task's scope".
-->

```

```

## Contract compliance

<!-- The contracts are authoritative. CONTRIBUTING.md §8. -->

- [ ] Every name I introduced is pinned in C1–C5, or this PR introduces no new names
- [ ] I did not add a dependency outside `gradle/libs.versions.toml`
- [ ] `lock-api` still has zero third-party dependencies, or this PR does not touch it
- [ ] I did not weaken, disable, or widen the tolerance of any existing test
- [ ] **I did not disable a fencing check to make something work**
- [ ] No real data, credentials, or real organisation names are introduced
- [ ] Any number I report carries the command, environment and date that produced it

<!--
If you cannot tick one of these, DO NOT delete it. Explain below. A recorded
deviation is a normal part of contributing here; an unrecorded one is
indistinguishable from a bug for everyone who comes after you.
-->

## Deviations from the specification

<!--
Any difference between what the spec said and what you built, however small and
however justified. Delete this section only if there genuinely are none.
-->

| | |
|---|---|
| **What** | The specification said X; the repository now has Y |
| **Why** | <!-- The specific reason. Not "cleaner" or "more idiomatic" --> |
| **Blast radius** | <!-- Which later tasks, contracts or documents are now inconsistent --> |
| **Contract impact** | <!-- None, or: which contract needs an amendment (open a contract-change issue) --> |

## For the reviewer

<!--
Optional but appreciated. Where should they look hardest? What did you find
uncertain? What did you decide not to do, and why?
-->

---

- [ ] My commits are signed off (`git commit -s`) under the [DCO](https://developercertificate.org/)
- [ ] If AI-assisted, a `Co-Authored-By` trailer names the tool ([AGENTS.md](../blob/main/AGENTS.md))
