#!/usr/bin/env bash

set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
cd "$repository_root"

tag_name="${1:-${GITHUB_REF_NAME:-}}"
main_ref="${2:-origin/main}"

if [[ ! "$tag_name" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "Release tag must be vMAJOR.MINOR.PATCH or a SemVer prerelease." >&2
  exit 1
fi

version="${tag_name#v}"
tag_ref="refs/tags/${tag_name}"

if [[ "$(git cat-file -t "$tag_ref" 2>/dev/null || true)" != "tag" ]]; then
  echo "Release tag must be an annotated Git tag: ${tag_name}" >&2
  exit 1
fi

tag_commit="$(git rev-list -n 1 "$tag_ref")"
if ! git merge-base --is-ancestor "$tag_commit" "$main_ref"; then
  echo "Release tag ${tag_name} must point to a commit already contained in ${main_ref}." >&2
  exit 1
fi

changelog_version="$(
  awk '/^## [0-9]+\.[0-9]+\.[0-9]+/ { print $2; exit }' CHANGELOG.md
)"
if [[ "$changelog_version" != "$version" ]]; then
  echo "CHANGELOG latest version ${changelog_version:-<missing>} does not match ${version}." >&2
  exit 1
fi

upgrade_guide="docs/upgrading-to-${version}.md"
if [[ ! -f "$upgrade_guide" ]]; then
  echo "Release upgrade guide is missing: ${upgrade_guide}" >&2
  exit 1
fi

echo "Release provenance verified: ${tag_name} -> ${tag_commit} on ${main_ref}"
