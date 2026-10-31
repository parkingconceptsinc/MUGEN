# Android APK updates

MUGEN APK checks the GitHub latest release from the native Settings screen. Releases must be tagged `android-vMAJOR.MINOR.PATCH` and include the signed APK asset. Both apps read their version from [`shared/release.json`](../shared/release.json); the proposed release is `0.16.0` (`versionCode 1600`).

## One-time signing setup

Android only permits an in-place update when the package ID and signing certificate match the installed app. Before publishing the first update, create a release keystore and configure these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore or passwords. Keep a secure backup. If an installed APK was signed with a different key (for example, an ephemeral debug key), Android will reject an in-place update. Verify signing compatibility before distributing an update; uninstalling can remove app-private data.

### Configure signing from your own computer

The signing key is not included in this repository or the cloud workspace. Do not paste private keys or passwords into a chat, issue, or pull request. On the computer holding the existing production keystore, install Java 17 and GitHub CLI, authenticate `gh` with repository secrets access, and run this from a checkout:

```sh
bash scripts/configure-android-signing.sh
```

The helper asks for the keystore path, alias, and passwords privately, proves that the private key can sign, shows its public certificate fingerprint for comparison with the installed app, then uploads the four secrets to `parkingconceptsinc/MUGEN` through standard input. It never prints passwords or includes them in command arguments. To compare an APK certificate, use `apksigner verify --print-certs path/to/installed-MUGEN.apk` from Android SDK Build Tools.

If this is the **first production signature**, use `bash scripts/configure-android-signing.sh --create` instead. Choose a location outside the checkout. The helper creates a password-protected RSA 4096 keystore without overwriting existing files and asks you to back it up before uploading secrets. Preserve that key and password for every future update. A new production signature cannot update an APK signed with another certificate.

If any signing secrets already exist, the helper stops before changing them. To repair incomplete configuration, use the **same production keystore** and `bash scripts/configure-android-signing.sh --replace`. Secret updates are separate GitHub requests: if a request fails, rerun with the same keystore and `--replace` to finish. Do not use this option to rotate the app's key.

The initial `0.16.0` publication failed before signing because `ANDROID_KEYSTORE_BASE64` was absent. After saving the secrets, publish from the current `main` branch with:

```sh
gh workflow run android-release.yml --ref main --repo parkingconceptsinc/MUGEN
```

You can also set these secrets in [repository Actions secrets](https://github.com/parkingconceptsinc/MUGEN/settings/secrets/actions) and run the publication workflow from `main` in GitHub Actions. Creating secrets requires access to repository settings; the repository connector available in this session cannot manage them.

## Publish a release

After configuring secrets, merging a change to `shared/release.json` (or the release workflow) into `main` publishes that version automatically. The Actions workflow can also be run manually from `main`, or triggered by pushing the matching tag such as `android-v0.16.0`. The tag must match the shared manifest. The workflow validates both apps, runs unit tests and lint, builds a signed release APK, attaches it to a GitHub Release, and then deploys the PWA from the same commit. The in-app updater detects the release, downloads the APK, and opens Android's installer. The user must approve installation; Android may also ask permission to install unknown apps.

Version codes are derived as `major * 10000 + minor * 100 + patch` and must increase monotonically. Do not publish a tag that would produce a version code lower than an already distributed build.

The release workflow reads the shared version, validates both apps, and creates the version tag on its exact tested commit when publishing the signed APK. Existing tags are never moved: if a version already points elsewhere, bump the shared version. A missing signing secret stops publication before any release is created. The PWA is deployed from the same workflow after the signed APK is published.
