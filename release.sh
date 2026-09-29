#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 0 ]; then
  echo "Version numbers are now chosen by the release PR. Run ./release.sh without arguments." >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required to request a release PR." >&2
  exit 1
fi

gh workflow run release.yml --ref main
echo "Release workflow requested for GitHub main. Review and merge the proposed release PR when ready."
