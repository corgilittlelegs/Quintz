#!/usr/bin/env bash
set -e

# Usage: ./release.sh <version> (e.g., ./release.sh 1.0.0 or ./release.sh v1.0.0)

if [ -z "$1" ]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 1.0.0"
  exit 1
fi

VERSION="${1#v}"
TAG="v${VERSION}"

# Check for uncommitted changes
if ! git diff-index --quiet HEAD --; then
  echo "Error: Working directory has uncommitted changes. Please commit or stash them first."
  exit 1
fi

echo "==> Preparing release ${TAG}..."

# Check if tag already exists
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "Error: Tag ${TAG} already exists."
  exit 1
fi

# Push current branch first
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
echo "==> Pushing ${CURRENT_BRANCH} to origin..."
git push origin "$CURRENT_BRANCH"

# Create and push the annotated tag
echo "==> Creating tag ${TAG}..."
git tag -a "$TAG" -m "Release ${TAG}"

echo "==> Pushing tag ${TAG} to origin..."
git push origin "$TAG"

echo ""
echo "🎉 Release ${TAG} triggered successfully!"
echo "GitHub Actions is now automatically building Quintz-${TAG}.apk and publishing the release."
echo "Track progress at: https://github.com/corgilittlelegs/Quintz/actions"
