# Mortimer Player — Samsung Tizen TV

This is a separate TV target. It does not replace or modify the existing Vite/React PWA or Android APK. Its visual direction is aligned to the PWA's dark surfaces and orange accent, adapted for a 10-foot screen.

## Current scope
- Media-first home screen and TV-friendly destination views.
- Mortimer PWA color tokens and typography hierarchy, with large artwork treatment and clear empty states.
- Directional focus navigation across the rail and content, Enter/OK activation, Escape/Back handling, and guarded Samsung media-key registration.
- Responsive 16:9 layout with a shorter-height fallback, visible focus, and reduced-motion support.
- Local-only preview; no required third-party fonts, images, or scripts.

## Design and agent skills
The project design contract is `DESIGN.md`. Claude Code project skills are in `../.claude/skills/`:
- `frontend-design`: visual hierarchy, art direction, consistency and critique.
- `mortimer-pwa-parity`: preserve PWA terminology and design behavior without pretending all browser features work on Tizen.
- `tizen-tv-ui`: remote navigation, safe areas, 10-foot layout and Tizen-specific boundaries.
- `tv-ui-quality`: release checks and evidence-based reporting.

## Validation
A GitHub Actions workflow at `.github/workflows/tizen-tv-checks.yml` runs:
- JavaScript syntax validation;
- static UI smoke checks;
- XML well-formedness validation for `config.xml`.

These checks are not a Tizen SDK build and do not establish physical-TV compatibility. BrowserAct was not available to capture an automated rendered screenshot in the current environment; a network restriction also prevented installing it. Visual inspection and real TV validation remain outstanding.

## Important compatibility limits
This is a polished front-end preview, not a production media player. The TV model code and firmware have not been confirmed. The screen does not yet enumerate USB files or the TV content database, load real library metadata/artwork, or play local media. Book/comic/photo browsing, queue, equalizer, subtitles, resume position, and full playback controls are currently honest UI states, not implemented features.

Samsung TV applications use the supported Tizen TV APIs and device media engine. Do not assume Android file access, browser folder-picker APIs, PWA storage behavior, FFmpeg/WASM, or any specific codec is available. Validate the Tizen Content API and AVPlay path against the exact model and firmware before claiming USB or codec support.

Tizen package signing is required for installation. A ZIP renamed to `.wgt` is not a signed, installable application. Use Tizen Studio and an appropriate Samsung certificate profile.

## Build and install with Tizen Studio
1. Confirm the full TV model code and firmware/Tizen version.
2. Install Tizen Studio with the Samsung TV extensions compatible with the target.
3. Create a certificate profile in Certificate Manager and authorize the physical TV according to Samsung's current procedure.
4. Import `tizen-tv` as a Tizen Web Application project.
5. Verify the `tizen:application` ID/package values and required privileges in the installed SDK. Keep only privileges required by APIs actually used.
6. Build and sign the WGT in Tizen Studio.
7. Test on the emulator and physical TV: cold start, screen edges/overscan, all arrow directions, OK, Back, visibility changes, USB enumeration, playback, subtitles, and each target media format.

## Next engineering milestones
1. Confirm exact TV model and firmware.
2. Validate and refine the layout in the emulator and physical TV.
3. Integrate real media indexing through the supported Tizen Content API and validate USB visibility.
4. Implement playback using the appropriate TV media engine, including error handling and subtitle behavior.
5. Connect library artwork, music queue, album/artist views, metadata and playback state.
6. Package a signed WGT and add a reproducible release workflow.
7. Compare behavior and design against the PWA/APK without merging their runtime dependencies.
