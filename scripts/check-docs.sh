#!/usr/bin/env bash
#
# check-docs.sh -- the documentation checks that CI runs, runnable locally.
#
#   ./scripts/check-docs.sh            # all checks
#   ./scripts/check-docs.sh links      # relative links resolve
#   ./scripts/check-docs.sh hygiene    # no credentials, community files present
#   ./scripts/check-docs.sh ledger     # tasks/ and the board agree
#
# Run this before opening a documentation pull request. Documentation is a
# first-class deliverable here -- the design is written before the code, so a
# broken cross-reference is a real defect.
#
# NOTE ON SHELL SAFETY: `grep` exits 1 when it matches nothing, which is a
# perfectly normal outcome for every filter below. Combined with `set -e` and
# `set -o pipefail` -- both of which GitHub Actions applies by default -- that
# turns "this file has no links" into a failed build. Every pipeline here is
# therefore explicitly guarded. This is the bug that broke the first CI run.

set -uo pipefail

cd "$(dirname "$0")/.."

MODE="${1:-all}"
RC=0

# GitHub Actions annotation if running in CI, plain text otherwise.
err() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    printf '::error file=%s::%s\n' "$1" "$2"
  else
    printf '  FAIL  %s: %s\n' "$1" "$2"
  fi
}

# ------------------------------------------------------------------ links ---
# Skipped deliberately:
#   ../blob/master/... , ../../issues?...  GitHub-relative URLs that only
#     resolve once rendered on github.com (used by issue and PR templates).
#   Cn-....md , T-0NN-...                 placeholders inside authoring templates.
check_links() {
  echo "== relative links =="
  local broken=0 checked=0 src target

  while IFS= read -r src; do
    while IFS= read -r target; do
      [ -z "$target" ] && continue
      checked=$((checked + 1))
      if [ ! -e "$(dirname "$src")/$target" ]; then
        err "$src" "broken link -> $target"
        broken=$((broken + 1))
      fi
    done < <(
      grep -oE '\]\([^)]+\)' "$src" 2>/dev/null \
        | sed -E 's/^\]\(//; s/\)$//' \
        | grep -vE '^(https?:|mailto:|#)' \
        | grep -vE '(\.\./)+(blob|tree|issues|pull|discussions|security)' \
        | grep -vE '(Cn-\.\.\.\.md|T-0NN|<.*>)' \
        | sed -E 's/#.*$//' \
        | grep -v '^$' \
        || true
    )
  done < <(find . -name '*.md' -not -path './.git/*' -not -path './_site/*' | sort)

  echo "  checked $checked link(s)"
  if [ "$broken" -gt 0 ]; then
    echo "  $broken broken relative link(s)."
    RC=1
  else
    echo "  OK -- all relative links resolve."
  fi
}

# ---------------------------------------------------------------- hygiene ---
check_hygiene() {
  echo "== repository hygiene =="

  # workflows/ and scripts/ are excluded because this check's own pattern
  # definition lives there and would otherwise match itself.
  local hits
  hits="$(grep -rInE '(BEGIN [A-Z ]*PRIVATE KEY|AIza[0-9A-Za-z_-]{35}|-----BEGIN CERTIFICATE)' \
            --exclude-dir=.git --exclude-dir=workflows --exclude-dir=scripts \
            --exclude-dir=_site . 2>/dev/null || true)"
  if [ -n "$hits" ]; then
    echo "$hits"
    err "SECURITY.md" "possible credential material committed"
    RC=1
  else
    echo "  OK -- no credential patterns."
  fi

  local missing=0 f
  for f in README.md LICENSE NOTICE CONTRIBUTING.md CODE_OF_CONDUCT.md \
           SECURITY.md GOVERNANCE.md SUPPORT.md MAINTAINERS.md ROADMAP.md \
           AGENTS.md .github/CODEOWNERS; do
    if [ ! -f "$f" ]; then
      err "$f" "required community health file is missing"
      missing=1
    fi
  done
  [ "$missing" -eq 0 ] && echo "  OK -- all community health files present." || RC=1
}

# ----------------------------------------------------------------- ledger ---
# Split tasks (T-016a / T-016b) share one specification file, so ids are
# compared with any trailing a/b stripped.
check_ledger() {
  echo "== task board =="
  local specs rows id

  specs="$(ls tasks/T-*.md 2>/dev/null | sed -E 's#.*/(T-[0-9]{3}).*#\1#' | sort -u || true)"
  rows="$(grep -oE '^\| \[?T-[0-9]{3}' tasks/README.md 2>/dev/null \
            | sed -E 's/^\| \[?//' | sort -u || true)"

  local bad=0
  while IFS= read -r id; do
    [ -z "$id" ] && continue
    grep -qx "$id" <<<"$rows" || { err "tasks/README.md" "$id has a specification but no ledger row"; bad=1; }
  done <<<"$specs"

  while IFS= read -r id; do
    [ -z "$id" ] && continue
    grep -qx "$id" <<<"$specs" || { err "tasks/README.md" "ledger row $id has no specification file"; bad=1; }
  done <<<"$rows"

  echo "  specifications: $(grep -c . <<<"$specs" || true), ledger ids: $(grep -c . <<<"$rows" || true)"
  [ "$bad" -eq 0 ] && echo "  OK -- board and specifications agree." || RC=1
}

case "$MODE" in
  links)   check_links ;;
  hygiene) check_hygiene ;;
  ledger)  check_ledger ;;
  all)     check_links; echo; check_hygiene; echo; check_ledger ;;
  *) echo "usage: $0 [links|hygiene|ledger|all]" >&2; exit 2 ;;
esac

echo
[ "$RC" -eq 0 ] && echo "all checks passed." || echo "CHECKS FAILED."
exit "$RC"
