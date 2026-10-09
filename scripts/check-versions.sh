#!/usr/bin/env bash
#
# check-versions.sh -- container image tags must match the version catalog.
#
#   ./scripts/check-versions.sh
#
# gradle/libs.versions.toml is the single place a version lives (C5 #ct5-catalog),
# but deploy/compose/compose.yaml and deploy/images/Dockerfile cannot read it, so
# they repeat the *-image versions as literal tags. Dependabot's image pull
# requests change only those literals. This check fails the build when a tag
# drifts from its catalog row, when an image has no catalog row at all, and when
# it finds no image to check -- an empty scan would otherwise pass vacuously.
#
# Only the two files below are scanned. A new compose file or Dockerfile that
# names an image must be added here, or its tags go unchecked.
#
# Same shell discipline as check-docs.sh: no `set -e`, and every grep that may
# match nothing is guarded.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

CATALOG="gradle/libs.versions.toml"
COMPOSE="deploy/compose/compose.yaml"
DOCKERFILE="deploy/images/Dockerfile"
RC=0
CHECKED=0

# GitHub Actions annotation if running in CI, plain text otherwise.
err() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    printf '::error file=%s,line=%s::%s\n' "$1" "$2" "$3"
  else
    printf '  FAIL  %s:%s: %s\n' "$1" "$2" "$3"
  fi
  RC=1
}

# The value of a [versions] key, or empty.
catalog_version() {
  sed -nE "s/^$1[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/p" "$CATALOG" | head -n 1
}

POSTGRES="$(catalog_version postgres-image)"
ETCD="$(catalog_version etcd-image)"
TEMURIN="$(catalog_version temurin-image)"
for pair in "postgres-image:$POSTGRES" "etcd-image:$ETCD" "temurin-image:$TEMURIN"; do
  [ -n "${pair#*:}" ] || err "$CATALOG" 1 "the catalog has no ${pair%%:*} version"
done

# check FILE LINE REF -- compare one image reference with its catalog row.
check() {
  local file="$1" line="$2" ref="$3" repo tag want key
  case "$ref" in
    "")    err "$file" "$line" "empty image reference"; return ;;
    *'$'*) err "$file" "$line" "$ref uses variable interpolation; it must be a literal tag this check can compare"; return ;;
  esac
  case "${ref##*/}" in
    *@*) err "$file" "$line" "$ref is pinned by digest; this check expects a tag that matches the catalog"; return ;;
    *:*) ;;
    *)   err "$file" "$line" "$ref has no tag; it must carry the catalog version"; return ;;
  esac
  repo="${ref%:*}"
  tag="${ref##*:}"
  case "$repo" in
    postgres)                     key="postgres-image"; want="$POSTGRES" ;;
    gcr.io/etcd-development/etcd) key="etcd-image";     want="v$ETCD" ;;
    eclipse-temurin)
      key="temurin-image"
      case "$tag" in
        *-jdk) want="$TEMURIN-jdk" ;;
        *-jre) want="$TEMURIN-jre" ;;
        *)     err "$file" "$line" "eclipse-temurin tag $tag is neither -jdk nor -jre"; return ;;
      esac ;;
    *)
      err "$file" "$line" "image $repo has no catalog row -- add a *-image version to $CATALOG and a rule to scripts/check-versions.sh"
      return ;;
  esac
  case "$want" in v|-jdk|-jre|"") return ;; esac   # no catalog row; already reported above
  CHECKED=$((CHECKED + 1))
  if [ "$tag" = "$want" ]; then
    echo "  OK    $file:$line  $ref = $key"
  else
    err "$file" "$line" "$repo is tagged $tag, but $CATALOG $key says $want"
  fi
}

echo "== image tags =="

found=0
while IFS=: read -r line rest; do
  [ -z "$line" ] && continue
  ref="$(sed -E 's/^[[:space:]]*image:[[:space:]]*//; s/[[:space:]]+#.*$//; s/["'\'']//g; s/[[:space:]]+$//' <<<"$rest")"
  found=$((found + 1))
  check "$COMPOSE" "$line" "$ref"
done <<<"$(grep -nE '^[[:space:]]*image:' "$COMPOSE" || true)"
[ "$found" -gt 0 ] || err "$COMPOSE" 1 "found no image: lines -- the check would pass vacuously"

# Instructions are case-insensitive and may be indented. FROM may name an
# earlier build stage instead of an image; those are skipped.
found=0
stages=" "
while IFS=: read -r line rest; do
  [ -z "$line" ] && continue
  read -r -a words <<<"$rest"
  i=1                                                 # words[0] is FROM
  while [ "$i" -lt "${#words[@]}" ] && [ "${words[$i]#--}" != "${words[$i]}" ]; do
    i=$((i + 1))                                      # --platform=...
  done
  ref="${words[$i]:-}"
  case "${words[$((i + 1))]:-}" in [Aa][Ss]) stages="$stages${words[$((i + 2))]:-} " ;; esac
  case "$stages" in *" $ref "*) continue ;; esac
  found=$((found + 1))
  check "$DOCKERFILE" "$line" "$ref"
done <<<"$(grep -niE '^[[:space:]]*FROM[[:space:]]' "$DOCKERFILE" || true)"
[ "$found" -gt 0 ] || err "$DOCKERFILE" 1 "found no FROM lines -- the check would pass vacuously"

if [ "$RC" -eq 0 ]; then
  echo "  OK -- $CHECKED image tags match the version catalog."
  echo
  echo "all checks passed."
else
  echo
  echo "CHECKS FAILED."
fi
exit "$RC"
