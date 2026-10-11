# Android APK updates

Mortimer Player checks the GitHub **latest release** from its Home screen. Publish releases with tags like `android-v0.7.3`; the release must include the signed APK asset.

## One-time signing setup

Android requires every in-place update to use the same application ID and signing certificate as the installed APK. Configure these repository Actions secrets before publishing releases:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the keystore and passwords backed up securely. Never commit the keystore or passwords to the repository. If the currently installed APK was signed with a different key (for example, a transient CI debug key), Android will reject an in-place update; uninstalling may erase app-private data. Verify signing compatibility before distributing the first update.

## Publish a release

After the signing secrets are configured, push a tag such as `android-v0.7.3`. The release workflow tests the Android project, builds a signed APK, and attaches it to a GitHub Release. The app can then detect, download, and hand the APK to Android's installer. Android requires the user to approve installation, and may ask to allow installs from Mortimer Player.

Version codes use `major * 10000 + minor * 100 + patch`, so version tags must increase monotonically.
