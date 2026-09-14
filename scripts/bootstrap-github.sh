#!/usr/bin/env bash
#
# bootstrap-github.sh -- turn the task board into a working GitHub project.
#
# Creates the label taxonomy, the eight milestones, and one issue per task
# specification, wired to its milestone with the right labels and its blockers
# listed. Run once, after the repository exists on GitHub.
#
#   ./scripts/bootstrap-github.sh --dry-run     # print what would happen
#   ./scripts/bootstrap-github.sh labels        # labels only
#   ./scripts/bootstrap-github.sh milestones    # milestones only
#   ./scripts/bootstrap-github.sh issues        # issues only
#   ./scripts/bootstrap-github.sh all           # everything
#
# Idempotent: re-running skips anything that already exists. Safe to re-run
# after adding a task specification.
#
# Requires: gh (authenticated), and a git remote pointing at the repository.

set -euo pipefail

DRY_RUN=0
MODE="all"
for arg in "$@"; do
  case "$arg" in
    --dry-run)                 DRY_RUN=1 ;;
    labels|milestones|issues|all) MODE="$arg" ;;
    -h|--help)                 sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")/.."

run() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  [dry-run] %s\n' "$*"
  else
    "$@"
  fi
}

need() { command -v "$1" >/dev/null || { echo "error: $1 is required" >&2; exit 1; }; }
need gh
need awk

if ! gh auth status >/dev/null 2>&1; then
  echo "error: gh is not authenticated. Run: gh auth login" >&2
  exit 1
fi

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
if [ -z "$REPO" ]; then
  echo "error: no GitHub repository detected for this directory." >&2
  echo "Create it first, e.g.:" >&2
  echo "  gh repo create distributed-lock-lab --public --source=. --remote=origin --push" >&2
  exit 1
fi
echo "repository: $REPO"
[ "$DRY_RUN" -eq 1 ] && echo "MODE: dry run -- nothing will be created"
echo

# ---------------------------------------------------------------- labels ----
# Parsed from .github/labels.yml so that file stays the single source of truth.
create_labels() {
  echo "== labels =="
  awk '
    /^- name:/ { if (n != "") emit(); n=$0; sub(/^- name: *"?/,"",n); sub(/"?$/,"",n); c=""; d="" ; next }
    /^  color:/ { c=$0; sub(/^  color: *"?/,"",c); sub(/"?$/,"",c); next }
    /^  description:/ { d=$0; sub(/^  description: *"?/,"",d); sub(/"?$/,"",d); next }
    END { if (n != "") emit() }
    function emit() { printf "%s\t%s\t%s\n", n, c, d }
  ' .github/labels.yml | while IFS=$'\t' read -r name color desc; do
    [ -z "$name" ] && continue
    printf '  %-24s' "$name"
    if [ "$DRY_RUN" -eq 1 ]; then
      echo "[dry-run] create/update"
    else
      gh label create "$name" --color "$color" --description "$desc" --force >/dev/null \
        && echo "ok" || echo "FAILED"
    fi
  done
  echo
}

# ------------------------------------------------------------ milestones ----
MILESTONES=(
  "M0 — Foundations"
  "M1 — PostgreSQL lock backend"
  "M2 — Protected resource and executor"
  "M3 — etcd backend"
  "M4 — Client SDK and correctness proof"
  "M5 — Cloud infrastructure"
  "M6 — Observability and SRE"
  "M7 — Benchmark and publication"
)

create_milestones() {
  echo "== milestones =="
  local existing
  existing="$(gh api "repos/$REPO/milestones?state=all" -q '.[].title' 2>/dev/null || true)"
  for m in "${MILESTONES[@]}"; do
    printf '  %-42s' "$m"
    if grep -Fxq "$m" <<<"$existing"; then
      echo "exists, skipped"
    elif [ "$DRY_RUN" -eq 1 ]; then
      echo "[dry-run] create"
    else
      gh api "repos/$REPO/milestones" -f title="$m" \
        -f description="See ROADMAP.md for exit criteria." >/dev/null \
        && echo "created" || echo "FAILED"
    fi
  done
  echo
}

milestone_for() {   # T-011 -> milestone title
  case "${1#T-}" in
    00[1-8]) echo "${MILESTONES[0]}" ;;
    01[0-7]) echo "${MILESTONES[1]}" ;;
    02[0-7]) echo "${MILESTONES[2]}" ;;
    03[0-4]) echo "${MILESTONES[3]}" ;;
    04[0-7]) echo "${MILESTONES[4]}" ;;
    05[0-9]) echo "${MILESTONES[5]}" ;;
    06[0-9]) echo "${MILESTONES[6]}" ;;
    07[0-5]) echo "${MILESTONES[7]}" ;;
    *)       echo "" ;;
  esac
}

# ---------------------------------------------------------------- issues ----
# One issue per specification. The ledger row supplies blockers and notes, so
# the issue and tasks/README.md cannot drift apart at creation time.
create_issues() {
  echo "== issues =="
  local existing
  existing="$(gh issue list --limit 500 --state all --json title -q '.[].title' 2>/dev/null || true)"

  for spec in tasks/T-*.md; do
    local id title ms row blockers labels body
    id="$(basename "$spec" | grep -oE '^T-[0-9]{3}')"
    title="$(head -1 "$spec" | sed -E 's/^# *//')"

    if grep -Fq "[$id]" <<<"$existing" || grep -Fq "$title" <<<"$existing"; then
      printf '  %-8s %s\n' "$id" "exists, skipped"
      continue
    fi

    ms="$(milestone_for "$id")"
    row="$(grep -m1 -E "^\| \[?${id}" tasks/README.md || true)"
    blockers="$(awk -F'|' '{gsub(/^ +| +$/,"",$4); print $4}' <<<"$row")"
    [ -z "$blockers" ] && blockers="—"

    labels="type:task,status:needs-triage"
    grep -q 'good first issue' <<<"$row"  && labels="$labels,good first issue"
    grep -q 'critical-path'    <<<"$row"  && labels="$labels,critical-path"
    grep -q '`safety`'         <<<"$row"  && labels="$labels,safety"
    grep -q 'needs:cloud'      <<<"$row"  && labels="$labels,needs:cloud"
    [ "$blockers" = "—" ]                 && labels="$labels,status:ready,help wanted"

    body="$(cat <<EOF
**Specification:** [\`$spec\`]($spec)
**Milestone:** $ms
**Blocked by:** $blockers

---

Read the specification in full before starting. It names its preconditions, deliverable files,
acceptance criteria, and the exact commands that verify it.

**To claim this**, comment \`/claim\` and a maintainer will assign it. One task at a time until you
have landed one; claims lapse after 14 days without a draft PR or a progress comment. See
[CONTRIBUTING.md](CONTRIBUTING.md#4-claiming-a-task).

### Definition of done

All six must hold — five out of six is not done ([details](CONTRIBUTING.md#7-definition-of-done)):

- [ ] \`./gradlew build\` succeeds from a clean checkout
- [ ] \`./gradlew spotlessCheck\` is green, and no unrelated file was reformatted
- [ ] The tests the specification names pass, and everything already green stayed green
- [ ] **The observability this task specifies actually emits** — scraped and observed, not merely called
- [ ] The ledger row in [\`tasks/README.md\`](tasks/README.md) is updated
- [ ] Deviations are recorded (what / why / blast radius / contract impact)

> **If the specification and a [contract](docs/04-contracts.md) disagree, the contract wins.**
> Open a contract-change issue rather than implementing either version.
EOF
)"

    printf '  %-8s' "$id"
    if [ "$DRY_RUN" -eq 1 ]; then
      echo "[dry-run] create | ms='$ms' | blocked-by='$blockers' | labels='$labels'"
    else
      gh issue create --title "[$id] $title" --body "$body" \
        --milestone "$ms" --label "$labels" >/dev/null \
        && echo "created" || echo "FAILED"
    fi
  done
  echo
}

case "$MODE" in
  labels)     create_labels ;;
  milestones) create_milestones ;;
  issues)     create_issues ;;
  all)        create_labels; create_milestones; create_issues ;;
esac

echo "done."
if [ "$DRY_RUN" -eq 0 ] && [ "$MODE" = "all" ]; then
  cat <<'EOF'

Next steps, by hand:
  1. Settings -> General: enable Discussions (SUPPORT.md links to them).
  2. Settings -> Branches: protect `main` -- require a PR, require the `build`,
     `docs` and `dco` checks, require linear history, no direct pushes.
  3. Settings -> Security: enable private vulnerability reporting (SECURITY.md
     links to the advisory form) and secret scanning with push protection.
  4. Add a reporting address to CODE_OF_CONDUCT.md -- it currently carries a
     placeholder marked ACTION REQUIRED.
  5. Pin the T-001 issue. It blocks every other Java task in the repository.
EOF
fi
