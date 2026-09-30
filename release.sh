#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Versions are proposed automatically. Run ./release.sh without a version." >&2
  exit 1
fi

gh workflow run release-please.yml
echo "Release Please will open or update a release pull request if there are releasable commits. Review and merge that PR when ready."
