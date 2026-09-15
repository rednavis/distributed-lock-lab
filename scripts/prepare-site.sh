#!/usr/bin/env bash
#
# prepare-site.sh -- stage the working tree for a Jekyll build.
#
# Run by .github/workflows/pages.yml immediately before `jekyll build`.
# It MUTATES the checkout, which is fine in CI (the checkout is disposable)
# and is the whole point: the committed files stay free of YAML front matter,
# because they are read primarily on github.com where front matter renders as
# an ugly table at the top of the page.
#
# WHY THIS IS NEEDED
#
# jekyll-optional-front-matter renders Markdown that has no front matter, which
# is how every document in this repository becomes a page. But the plugin
# deliberately SKIPS files whose basename is one of README, LICENSE,
# CONTRIBUTING, CODE_OF_CONDUCT, ISSUE_TEMPLATE or PULL_REQUEST_TEMPLATE -- it
# assumes those are repository metadata rather than content.
#
# Here they are content, and four of them are among the most important pages on
# the site. So this script gives exactly those files an empty front-matter
# block, which is enough to make Jekyll treat them as pages. Their title and
# nav_order still come from the `defaults` in _config.yml, so nothing about the
# navigation is duplicated here.
#
# Run it locally only against a throwaway copy; it edits files in place.

set -euo pipefail

cd "$(dirname "$0")/.."

if [ "${ALLOW_MUTATE:-}" != "1" ] && [ -z "${GITHUB_ACTIONS:-}" ]; then
  cat >&2 <<'EOF'
refusing to run: this script rewrites files in place.

It is meant for CI, where the checkout is disposable. To run it anyway (for
example against a scratch copy), set ALLOW_MUTATE=1.
EOF
  exit 1
fi

# The files jekyll-optional-front-matter will not touch on its own.
TARGETS=(
  README.md
  CONTRIBUTING.md
  CODE_OF_CONDUCT.md
  docs/README.md
  tasks/README.md
)

echo "== staging pages that jekyll-optional-front-matter skips =="
for f in "${TARGETS[@]}"; do
  if [ ! -f "$f" ]; then
    echo "  skip     $f (not present)"
    continue
  fi
  if head -1 "$f" | grep -qx -- '---'; then
    echo "  already  $f"
    continue
  fi
  printf -- '---\n---\n\n%s' "$(cat "$f")" > "$f.tmp" && mv "$f.tmp" "$f"
  echo "  staged   $f"
done

echo
echo "done. ${#TARGETS[@]} target(s) considered."
