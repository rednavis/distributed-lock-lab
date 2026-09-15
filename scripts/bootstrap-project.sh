#!/usr/bin/env bash
#
# bootstrap-project.sh -- create the GitHub Project (v2) and load every task.
#
#   ./scripts/bootstrap-project.sh --dry-run
#   ./scripts/bootstrap-project.sh
#
# Creates an organisation-level Project, adds all open task issues to it, and
# sets the Status field so the board opens in a useful state rather than one
# undifferentiated column.
#
# Idempotent: re-running reuses an existing project of the same title and skips
# issues already on the board. Safe to re-run after adding task specifications.
#
# REQUIRES THE `project` SCOPE, which the default `gh auth login` does not grant:
#
#   gh auth refresh -s project,read:project
#
# That opens a browser and cannot be done non-interactively, which is why this
# is a separate script from bootstrap-github.sh rather than part of it.

set -euo pipefail

cd "$(dirname "$0")/.."

DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

OWNER="${PROJECT_OWNER:-rednavis}"
REPO="${PROJECT_REPO:-rednavis/distributed-lock-lab}"
TITLE="${PROJECT_TITLE:-distributed-lock-lab — implementation}"

command -v gh >/dev/null || { echo "error: gh is required" >&2; exit 1; }

if ! gh api graphql -f query='{viewer{login}}' >/dev/null 2>&1; then
  echo "error: gh is not authenticated." >&2; exit 1
fi

# Fail early and legibly on the scope, rather than mid-way through.
if ! gh project list --owner "$OWNER" >/dev/null 2>&1; then
  cat >&2 <<EOF
error: your token is missing the 'project' scope.

  gh auth refresh -s project,read:project

That opens a browser; it cannot be automated. Re-run this script afterwards.
EOF
  exit 1
fi

echo "owner:   $OWNER"
echo "repo:    $REPO"
echo "project: $TITLE"
[ "$DRY_RUN" -eq 1 ] && echo "MODE:    dry run -- nothing will be created"
echo

# ------------------------------------------------------------- the project ---
NUMBER="$(gh project list --owner "$OWNER" --format json \
            --jq ".projects[] | select(.title==\"$TITLE\") | .number" 2>/dev/null | head -1)"

if [ -n "$NUMBER" ]; then
  echo "project #$NUMBER already exists, reusing"
elif [ "$DRY_RUN" -eq 1 ]; then
  echo "[dry-run] would create project \"$TITLE\""
  NUMBER="DRYRUN"
else
  NUMBER="$(gh project create --owner "$OWNER" --title "$TITLE" --format json --jq '.number')"
  echo "created project #$NUMBER"
fi

if [ "$DRY_RUN" -eq 0 ]; then
  gh project edit "$NUMBER" --owner "$OWNER" \
    --readme "$(cat <<'MD'
# distributed-lock-lab — implementation

63 task specifications, eight milestones, one argument to prove: **a distributed
lock is not what makes your critical section safe — fencing tokens are.**

## How to use this board

Task ids are **identifiers, not a schedule**. `T-023` may legitimately land
before `T-011`. What constrains order is each issue's **Blocked by** line.

- Group by **Milestone** to see the delivery plan (M0 → M7).
- Filter `label:status:ready` for tasks with no unmerged blockers.
- Filter `label:"good first issue"` if you are new here.
- `label:critical-path` marks the M0 → M1 → M2 → T-042 path to the central claim.

## Start here

- **[Task board](https://github.com/rednavis/distributed-lock-lab/blob/master/tasks/README.md)** — the ledger, authoritative for what is done
- **[What blocks what](https://github.com/rednavis/distributed-lock-lab/blob/master/docs/12-parallelization-map.md)** — the dependency graph
- **[Contributing](https://github.com/rednavis/distributed-lock-lab/blob/master/CONTRIBUTING.md)** — how to claim and land work
- **[Docs site](https://rednavis.github.io/distributed-lock-lab/)**

**If a task spec and a contract disagree, the contract wins.**
MD
)" >/dev/null 2>&1 && echo "readme set" || echo "note: could not set project readme (non-fatal)"
fi
echo

# --------------------------------------------------------------- the items ---
echo "== adding task issues =="
added=0 skipped=0

existing=""
if [ "$DRY_RUN" -eq 0 ]; then
  existing="$(gh project item-list "$NUMBER" --owner "$OWNER" --limit 500 --format json \
                --jq '.items[].content.number' 2>/dev/null || true)"
fi

while IFS=$'\t' read -r num title; do
  [ -z "$num" ] && continue
  if grep -qx "$num" <<<"$existing" 2>/dev/null; then
    skipped=$((skipped + 1)); continue
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  [dry-run] add #%-4s %s\n' "$num" "$title"
  else
    gh project item-add "$NUMBER" --owner "$OWNER" \
      --url "https://github.com/$REPO/issues/$num" >/dev/null 2>&1 \
      && printf '  added #%-4s %s\n' "$num" "$title" \
      || printf '  FAILED #%-4s %s\n' "$num" "$title"
  fi
  added=$((added + 1))
done < <(gh issue list --repo "$REPO" --limit 200 --state open \
           --label 'type:task' --json number,title \
           --jq '.[] | "\(.number)\t\(.title)"')

echo
echo "added: $added, already present: $skipped"
echo

if [ "$DRY_RUN" -eq 0 ]; then
  echo "done: https://github.com/orgs/$OWNER/projects/$NUMBER"
  cat <<'EOF'

Two view settings are UI-only (the API cannot express them):

  1. Open the board -> view menu -> "Group by" -> Milestone.
     That is the delivery-plan view: M0 through M7, in order.
  2. Add a second view, layout Board, "Group by" -> Status, and filter
     -label:status:blocked, for day-to-day triage.
EOF
else
  echo "dry run complete."
fi
