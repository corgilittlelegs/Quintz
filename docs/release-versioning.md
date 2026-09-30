# Release versions

Quintz uses two version numbers:

- `versionName` is the user-facing SemVer release tag. Release Please proposes it
  in a pull request from Conventional Commit messages. From `1.2.10`, `fix:`
  proposes `1.2.11`, `feat:` proposes `1.3.0`, and `feat!:` or a
  `BREAKING CHANGE:` footer proposes `2.0.0`. The highest bump in the set of
  unreleased commits wins. Review the proposed release PR before merging it.
- Android `versionCode` is independent of SemVer. The release workflow starts
  from published `v1.2.10` (`10210`) and adds one for each later version tag on
  the release line. This gives every new APK a higher update code without
  limiting patch or minor numbers to 99. Release tags must follow the same Git
  history and remain in place.

After merging a release PR, Release Please creates a draft GitHub release and
tag. The APK workflow tests, builds, signs, and attaches the APK, then publishes
the release. A failed build leaves the release draft for investigation. GitHub's
default token does not start a separate tag workflow for tags it creates, so
the Release Please workflow calls the APK workflow directly.

To refresh the proposal manually, run `./release.sh` after this workflow is on
GitHub. Do not create a new version tag by hand: the manifest and changelog
would not advance with it. The APK workflow's manual option is for rebuilding
the latest existing tag.
