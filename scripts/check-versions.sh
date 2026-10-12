#!/usr/bin/env bash
#
# check-versions.sh -- versions outside Gradle must come from the version catalog.
#
#   ./scripts/check-versions.sh               # run every check
#   ./scripts/check-versions.sh --get <key>   # print one [versions] value (used by CI)
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
# CI tools have no tag to compare: .github/workflows/infra.yml reads the
# terraform and tflint versions with --get, and the second check fails if it
# ever carries a literal version again.
#
# Same shell discipline as check-docs.sh: no `set -e`, and every grep that may
# match nothing is guarded.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

CATALOG="gradle/libs.versions.toml"
COMPOSE="deploy/compose/compose.yaml"
DOCKERFILE="deploy/images/Dockerfile"
INFRA=".github/workflows/infra.yml"
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

if [ "${1:-}" = "--get" ]; then
  # The key goes into a sed pattern, so only plain catalog names are accepted.
  case "${2:-}" in
    "" | *[!a-z0-9-]*) echo "usage: $0 --get <key>   (a [versions] name such as terraform)" >&2; exit 2 ;;
  esac
  [ $# -eq 2 ] || { echo "usage: $0 --get <key>" >&2; exit 2; }
  value="$(catalog_version "$2")"
  [ -n "$value" ] || { echo "error: the catalog has no $2 version" >&2; exit 2; }
  printf '%s\n' "$value"
  exit 0
fi
[ $# -eq 0 ] || { echo "usage: $0 [--get <key>]" >&2; exit 2; }

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

[ "$RC" -eq 0 ] && echo "  OK -- $CHECKED image tags match the version catalog."

echo
echo "== workflow tool versions =="
before="$RC"
RC=0
for key in terraform tflint; do
  [ -n "$(catalog_version "$key")" ] || err "$CATALOG" 1 "the catalog has no $key version"
done
# Each input must be exactly the matching output of the versions step. Anything
# else -- a literal, a typo in the output name, a `|| fallback` -- either pins a
# version outside the catalog or leaves the input empty, and both setup actions
# then install the latest release without a word.
for key in terraform tflint; do
  name="${key}_version"
  want="\${{ steps.versions.outputs.$key }}"
  hits="$(grep -nE "^[[:space:]]*$name:" "$INFRA" || true)"
  if [ -z "$hits" ]; then
    err "$INFRA" 1 "found no $name -- the check would pass vacuously"
  fi
  while IFS=: read -r line rest; do
    [ -z "$line" ] && continue
    value="$(sed -E "s/^[[:space:]]*$name:[[:space:]]*//; s/[[:space:]]+#.*\$//; s/^[\"']//; s/[\"']\$//" <<<"$rest")"
    if [ "$value" = "$want" ]; then
      echo "  OK    $INFRA:$line  $name = $value"
    else
      err "$INFRA" "$line" "$name must be exactly $want, found $value"
    fi
  done <<<"$hits"
  # The producer side: the versions step must read this key from the catalog.
  grep -qF -- "check-versions.sh --get $key)" "$INFRA" \
    || err "$INFRA" 1 "the versions step does not read $key with scripts/check-versions.sh --get $key"
  grep -qE "echo \"$key=" "$INFRA" \
    || err "$INFRA" 1 "the versions step does not write the $key output"
done
grep -qE '^[[:space:]]*id:[[:space:]]*versions[[:space:]]*$' "$INFRA" \
  || err "$INFRA" 1 "no step has id: versions"
[ "$RC" -eq 0 ] && echo "  OK -- infra.yml reads its tool versions from the catalog."
[ "$before" -eq 0 ] || RC=1

if [ "$RC" -eq 0 ]; then
  echo
  echo "all checks passed."
else
  echo
  echo "CHECKS FAILED."
fi
exit "$RC"
