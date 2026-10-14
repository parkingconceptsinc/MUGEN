# Mortimer Player — Samsung Tizen TV

The TV app is a **separate target** inside this repository. It does not replace or modify the existing PWA or Android APK.

## Current implementation

- PWA-aligned dark theme, orange `#ff7849` accent, large TV-safe type, left rail, home artwork, and explicit empty/error states.
- Primary navigation is intentionally limited to implemented workflows (Home, Music, Videos, Media Sources, Now Playing, Settings); planned Books, Comics, Photos, Queue and Equalizer destinations do not crowd the main rail.
- Home uses three high-priority destinations and responsive cards; directional focus scrolls focused controls into view for long media lists.
- Multi-input navigation: directional TV remote, keyboard arrows/Enter/Escape/Backspace, native Tab focus, mouse hover/click, and pointer-aware focus styling. Native input and seek controls retain their own keyboard behavior.
- Guarded Samsung media-key handling.
- Dedicated Music, Videos, Media Sources, unified All formats library, and a Photos screen with separate album and art-gallery layouts.
- Photo discovery scans mounted removable storage and standard Pictures/Images/DCIM/Downloads locations, with bounded traversal to protect TV memory. Image thumbnails open in the built-in full-screen image/document viewer where the Samsung TV WebView supports the format.
- Central format registry recognizes common audio/video, PDF, plain-text, ebook, comic archive, image, subtitle, and Office extensions.
- Built-in reader shell: plain-text/Markdown/CSV/JSON/XML/subtitle preview via the Tizen filesystem stream API, plus PDF iframe and image viewer attempts where the TV WebView supports them.
- Unsupported reader engines are explicitly identified rather than reporting false success.
- A TV media adapter in `media.js` that uses the documented Tizen filesystem APIs when running inside a compatible Samsung TV runtime:
  - lists mounted storage devices;
  - lists standard TV folders and mounted external storage;
  - navigates folders and identifies common audio/video filename extensions;
  - performs a bounded background media scan for the Music or Videos view;
  - converts supported filesystem paths to file URIs;
  - requests playback through Samsung AVPlay with playback controls, seeking, progress, and visible error states.
- Media/AVPlay APIs are guarded: a desktop browser preview says that TV storage/playback APIs are unavailable instead of pretending to scan a USB drive.
- No third-party image/font/CDN is needed for the base UI.

## USB file management

The media-source browser now exposes a Manage control for each listed USB file and folder. It offers copy, move, and delete operations through the Tizen filesystem API. Copy and move ask for an existing destination folder path on a connected removable drive; overwriting is disabled. Delete requires confirmation, and deleting a folder is recursive. Mutations are restricted to paths beneath a currently mounted removable-storage root.

These controls require the app's filesystem write permission and a Tizen runtime that grants write access to the USB filesystem. The desktop preview cannot validate physical USB writes. Before relying on this feature, test copy/move/delete on a non-critical test USB on the target Samsung TV, including full storage, read-only media, disconnect during operation, and duplicate destination names. TV remote behavior for native prompt and confirm dialogs also needs physical-device validation. Never test deletion on a drive containing the only copy of important files.

## Skills and design contract

Read `DESIGN.md` and the Claude Code project skills in `../.claude/skills/`:
- `frontend-design`
- `mortimer-pwa-parity`
- `tizen-tv-ui`
- `tv-ui-quality`

## Build checks

Use [TV-TEST-CHECKLIST.md](TV-TEST-CHECKLIST.md) for the emulator and physical-TV validation matrix.

GitHub Actions workflow `.github/workflows/tizen-tv-checks.yml` checks JavaScript syntax for both scripts, TV UI smoke assertions, and XML well-formedness. These are static checks only; they do not compile or sign a WGT and do not confirm a TV API works on the target firmware.

## UI audit status (2026-10-14)

- Static GitHub Actions checks passed for the navigation/layout refinement and smoke-test assertions: [run details](https://github.com/parkingconceptsinc/MUGEN/actions/runs/38597166502).
- Desktop-browser and physical-TV visual inspection are **not run** in this environment. Responsive CSS rules are present, but a human visual pass at 1920×1080, 1280×720 and short-height viewports remains required.
- Samsung remote behavior, USB browsing, AVPlay playback and signed WGT installation remain hardware/SDK validation tasks; static checks do not prove those paths work on the target TV.

## Important limits

**USB enumeration and AVPlay integration are implemented against Samsung's documented APIs, but they have not yet been verified on your physical TV or in the Samsung TV emulator.** Actual availability depends on the exact TV model, firmware, runtime, and application privileges.

- The full TV model code has not been confirmed. The manifest currently targets Tizen 5.5 as a provisional baseline, based on the resemblance to a 2020 TU-series TV; do not treat that as an exact model identification.
- The file scan is deliberately bounded (up to 240 folders and 1,200 matching files) to avoid freezing the TV. Use Media Sources to browse deeper folders.
- Filename extensions only suggest media type; they do not guarantee a codec/container will decode. AVPlay reports device-specific failures. Test real files on the television.
- **Full codec coverage is not guaranteed:** extensions only identify containers; audio/video codecs and profiles are constrained by the TV hardware and Samsung AVPlay. MKV, FLAC, HEVC, etc. can still fail depending on the internal codec/profile.
- **Not yet readable:** EPUB/MOBI/AZW/FB2, CBZ/CBR/CB7/CBT, Word/Excel/PowerPoint, and compressed archives are recognized but need bundled offline parsing/rendering engines. A file registry alone does not decode them.
- PDF and image rendering depend on the TV WebView and are not verified on physical hardware. Text previews are capped at 250 KB to protect TV memory.
- Album art, embedded metadata, resume position, playlists/queue, equalizer, and richer book/comic readers remain pending.
- Photo gallery scan and layouts are implemented in the source, but image decoding, thumbnails, remote navigation, and performance with large USB collections still require validation on the target TV. Image extensions are not a guarantee that the firmware can decode every file.
- Playback controls and seek behavior are wired to AVPlay, but their real operation still needs emulator/TV validation.

## Official API references

- [Samsung Smart TV Quick-start Guide](https://developer.samsung.com/smarttv/develop/getting-started/quick-start-guide.html)
- [Tizen Filesystem API](https://developer.samsung.com/smarttv/develop/api-references/tizen-web-device-api-references/filesystem-api.html)
- [Handling USB Storages](https://developer.samsung.com/smarttv/develop/guides/data-handling/handling-usb-storages.html)
- [Playback Using AVPlay](https://developer.samsung.com/smarttv/develop/guides/multimedia/media-playback/using-avplay.html)

## Package artifact

The GitHub Actions workflow `.github/workflows/mugen-tizen-package.yml` validates the TV source and publishes a downloadable artifact named **MUGEN-Tizen-TV-source** (ZIP plus SHA-256 checksum) for 30 days. Run it from **Actions → MUGEN Tizen TV package → Run workflow**, or push a change to `feature/tizen-tv`.

**This ZIP is a source bundle, not an installable TV application.** A Samsung Tizen install package must be built by a compatible Tizen Studio/TV SDK and signed with an authorized Samsung certificate profile to produce a valid `.wgt`. The CI workflow does not create or sign a WGT, and it does not contain certificates or private keys.

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


## Virtual TV test bench

Open `virtual-tv.html` in a browser served from the repository's `tizen-tv/` folder to run the MUGEN interface against a simulated Samsung Tizen filesystem and AVPlay engine. It includes mock USB media and test cases for navigation, audio, video, photos, USB copy, and format/reader-state reporting. Use the viewport buttons to inspect responsive layouts. All storage changes occur only in mock memory. A passing virtual test is not evidence of real codec, firmware, remote-control, or WGT-install compatibility.

See `AUDIT-FINDINGS.md` for the source audit and `TV-TEST-CHECKLIST.md` for physical-device validation.
