# Release process

Normal pushes to `main` run unit tests and full Android lint, then update a release pull request. The pull request proposes the next patch version and changelog. Merging it is the publication approval; no release is published from an ordinary code push.

Before the first run, enable **Settings → Actions → General → Workflow permissions → Allow GitHub Actions to create and approve pull requests** for this repository. The workflow uses the built-in `GITHUB_TOKEN` to open the release PR and create a draft release. The nine signing secrets listed in [release-signing.md](release-signing.md) remain required for the APK build.

The manifest starts at `1.2.10`, matching the latest release tag when this process was added. The next release is expected to be `v1.2.11`. Versions intentionally advance by one patch number, matching the existing Quintz release sequence.

1. Push normal code changes to `main` and let **Release APK** finish its tests and lint. The action opens or updates a release PR.
2. Review the proposed version and changelog. Merge the release PR only when its included code is ready for publication.
3. The merge triggers **Release APK** again. It stages a draft GitHub release, tests and lints the tagged commit, builds and signs the APK, uploads the APK and SHA-256 file, then publishes the release.

If the build or signing fails after a draft release is created, the release stays unpublished. After correcting the problem, open **Actions → Release APK → Run workflow**, select `main`, and enter the draft version (for example `v1.2.11`) in **retry_tag**. A retry refuses to alter an already published release.

`./release.sh` is an optional shortcut that requests the release workflow. It does not tag or publish directly. Release PRs created with `GITHUB_TOKEN` do not trigger separate PR workflows, so the release workflow tests `main` before proposing a PR and tests the exact tagged commit again before publishing.
