# MUGEN 0.17.6 — LibVLC Android integration

## Architecture

- **Android video:** VideoLAN LibVLC 3.6.1 via `org.videolan.android:libvlc-all`, using native `VLCVideoLayout`. This is the default video engine. Seek, pause, resume position, basic error handling and a Media3 fallback action are provided.
- **Android music / Android Auto:** the existing shared Media3 `MediaLibraryService` is retained. Before opening LibVLC, the Media3 player is paused so the engines do not compete for audio output.
- **PWA:** browser Media APIs, IndexedDB persistence, media capabilities and optional local transcoding. Native LibVLC is not available from a normal PWA.
- **Samsung Tizen:** Samsung AVPlay with its supported Tizen codecs and USB filesystem API. The APK's LibVLC AAR cannot be reused in a Tizen web app.

## Android access and safety

Import with the existing Android Storage Access Framework (SAF) picker. MUGEN opens `content://` entries as read-only file descriptors; it does not reinterpret opaque document IDs as filesystem paths. Permissions come from the existing import workflow. Close and re-open a file if it was removed from USB/SD. The app saves progress when video playback is paused and destroyed.

The new VLC player is not the Android Auto playback engine; Android Auto remains audio-only through Media3. The existing Media3 video dialogue is retained as an explicit fallback. Native video controls need physical-device acceptance before the release can be called production-ready.

## Third-party notices

LibVLC is a VideoLAN component distributed under LGPL-2.1-or-later terms. Before distributing APKs, ship the required license notices, retain applicable copyright credits, make corresponding source/license details available and confirm compatibility with any linked native plugins. VLC application GPL licensing and LibVLC library licensing are distinct. See https://www.videolan.org/ and https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html.

## Verification gates

1. CI: `npm ci && npm test && npm run build && npm run build:tizen`; Android `gradle testDebugUnitTest lintDebug assembleDebug`.
2. Test local MP4/H.264/AAC, MKV/HEVC, MKV/AC3, FLAC and long-duration seeking on physical devices; never infer codec compatibility solely from the filename.
3. Reopen an SAF video from USB and SD after app restart, test removing media mid-playback and missing/revoked permissions.
4. Start music, open a video, return to the audio queue, test Bluetooth/headphone behavior and verify the music engine remains responsive.
5. Try `Use Media3` from the VLC video screen, and `VLC Engine` from the Media3 video dialogue.
6. Validate phone/tablet rotation, background/foreground and subtitle overlays.
7. Production Android release requires the original matching keystore; Samsung WGT requires Samsung developer certificates and physical TV validation.

**Status:** Source implementation added; compilation and device behavior must be verified in CI/hardware. Not a claim of a completed production release.

## Tizen TV timeline controls

The Samsung AVPlay adapter now exposes a state-checked duration/current-position timeline and bounded ±10-second seeks from on-screen buttons or supported remote media keys. In-flight callbacks from previous videos are ignored. This remains subject to Tizen firmware/codec support and physical remote testing.
