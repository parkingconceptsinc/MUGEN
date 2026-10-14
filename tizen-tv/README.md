# Mortimer Player — Samsung Tizen TV

The TV app is a **separate target** inside this repository. It does not replace or modify the existing PWA or Android APK.

## Current implementation

- PWA-aligned dark theme, orange `#ff7849` accent, large TV-safe type, left rail, home artwork, and explicit empty/error states.
- Directional remote navigation, visible focus, Enter/OK, Back/Escape, and guarded Samsung media-key handling.
- Dedicated Music, Videos, and Media Sources screens.
- A TV media adapter in `media.js` that uses the documented Tizen filesystem APIs when running inside a compatible Samsung TV runtime:
  - lists mounted storage devices;
  - lists standard TV folders and mounted external storage;
  - navigates folders and identifies common audio/video filename extensions;
  - performs a bounded background media scan for the Music or Videos view;
  - converts supported filesystem paths to file URIs;
  - requests playback through Samsung AVPlay with playback controls, seeking, progress, and visible error states.
- Media/AVPlay APIs are guarded: a desktop browser preview says that TV storage/playback APIs are unavailable instead of pretending to scan a USB drive.
- No third-party image/font/CDN is needed for the base UI.

## Skills and design contract

Read `DESIGN.md` and the Claude Code project skills in `../.claude/skills/`:
- `frontend-design`
- `mortimer-pwa-parity`
- `tizen-tv-ui`
- `tv-ui-quality`

## Build checks

GitHub Actions workflow `.github/workflows/tizen-tv-checks.yml` checks JavaScript syntax for both scripts, TV UI smoke assertions, and XML well-formedness. These are static checks only; they do not compile or sign a WGT and do not confirm a TV API works on the target firmware.

## Important limits

**USB enumeration and AVPlay integration are implemented against Samsung's documented APIs, but they have not yet been verified on your physical TV or in the Samsung TV emulator.** Actual availability depends on the exact TV model, firmware, runtime, and application privileges.

- The full TV model code has not been confirmed. The manifest currently targets Tizen 5.5 as a provisional baseline, based on the resemblance to a 2020 TU-series TV; do not treat that as an exact model identification.
- The file scan is deliberately bounded (up to 240 folders and 1,200 matching files) to avoid freezing the TV. Use Media Sources to browse deeper folders.
- Filename extensions only suggest media type; they do not guarantee a codec/container will decode. AVPlay reports device-specific failures. Test real files on the television.
- Album art, embedded metadata, subtitles, resume position, playlists/queue, equalizer, and TV-specific books/comics/photos experiences are not fully implemented yet.
- Playback controls and seek behavior are wired to AVPlay, but their real operation still needs emulator/TV validation.

## Official API references

- [Samsung Smart TV Quick-start Guide](https://developer.samsung.com/smarttv/develop/getting-started/quick-start-guide.html)
- [Tizen Filesystem API](https://developer.samsung.com/smarttv/develop/api-references/tizen-web-device-api-references/filesystem-api.html)
- [Handling USB Storages](https://developer.samsung.com/smarttv/develop/guides/data-handling/handling-usb-storages.html)
- [Playback Using AVPlay](https://developer.samsung.com/smarttv/develop/guides/multimedia/media-playback/using-avplay.html)

## Build and install

1. Confirm the complete model code and firmware through **Settings → Support → About This TV**.
2. Install Tizen Studio with Samsung TV Extensions compatible with that model.
3. Create/authorize a Samsung certificate profile as required for the physical TV.
4. Import `tizen-tv` as a Tizen Web Application project and verify the manifest against the installed SDK.
5. Build and sign the WGT. A ZIP renamed to `.wgt` is not a valid signed application.
6. Test on the emulator and physical TV: cold start, USB mount/unmount, folder navigation, Music scan, Videos scan, play/pause/stop, seek, Back, removed-drive errors, and every media format you intend to use.

## Remaining milestones

1. Confirm model and platform.
2. Run the UI and media flow on the Samsung TV emulator.
3. Test real USB drives and media files on the physical TV.
4. Add metadata/artwork and resume state after confirming reliable media identity/path behavior.
5. Finish queue, subtitles, and optional books/comics/photos experiences based on supported APIs.
6. Produce a signed WGT and a repeatable release flow once SDK and device validation passes.
