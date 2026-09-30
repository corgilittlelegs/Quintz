#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )) || [[ "${1:-patch}" != patch && "${1:-patch}" != minor && "${1:-patch}" != major ]]; then
  echo "Usage: ./release.sh [patch|minor|major]" >&2
  exit 1
fi

gh workflow run release.yml --ref main -f bump="${1:-patch}"
echo "Release workflow requested. Check GitHub Actions for the test, build, signing, and publication result."
