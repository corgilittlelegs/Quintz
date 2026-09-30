# Release versions

Use VS Code to commit and sync code changes to `main`. Syncing code does not
publish an APK. When ready, open **GitHub → Actions → Release APK → Run workflow**,
keep the branch set to `main`, choose a version increase, and run it:

- **patch** (default): fixes and maintenance, for example `1.3.0` → `1.3.1`.
- **minor**: new features, for example `1.3.0` → `1.4.0`.
- **major**: breaking changes, for example `1.3.0` → `2.0.0`.

The workflow calculates the new version from the latest version tag, runs unit
tests and full lint, builds and signs the APK, then creates the tag and public
GitHub release with the APK, checksum, and commit-based notes. Failed tests,
lint, building, or signing leave no new tag or release. It rejects a run from
another branch, an unchanged commit, or a `main` branch that advances during
the build. A release never adds a commit to `main`, so the next VS Code Sync
does not need to catch up with a release commit.

The Android `versionCode` is independent of the user-facing version. It starts
from published `v1.2.10` (`10210`) and increases by one for each later version
tag on the release line. Keep release tags intact; they are used to calculate
future update codes.

The optional `./release.sh [patch|minor|major]` command starts the same manual
workflow. The GitHub Actions button does not require a terminal or extension.
