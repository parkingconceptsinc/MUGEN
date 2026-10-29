# Android APK updates

MUGEN APK checks the GitHub latest release from the native Settings screen. Releases must be tagged `android-vMAJOR.MINOR.PATCH` and include the signed APK asset. The current Android version is `0.13.1` (`versionCode 1301`).

## One-time signing setup

Android only permits an in-place update when the package ID and signing certificate match the installed app. Before publishing the first update, create a release keystore and configure these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore or passwords. Keep a secure backup. If an installed APK was signed with a different key (for example, an ephemeral debug key), Android will reject an in-place update. Verify signing compatibility before distributing an update; uninstalling can remove app-private data.

## Publish a release

After configuring secrets, push a tag such as `android-v0.13.1`. The workflow runs unit tests, lint, builds a signed release APK, and attaches it to a GitHub Release. The in-app updater detects the release, downloads the APK, and opens Android's installer. The user must approve installation; Android may also ask permission to install unknown apps.

Version codes are derived as `major * 10000 + minor * 100 + patch` and must increase monotonically. Do not publish a tag that would produce a version code lower than an already distributed build.
