# Mortimer Player — Samsung Tizen TV

This is a separate TV target. It does not replace the existing Vite/React PWA or Android APK.

## Current scope
- 10-foot interface designed for 16:9 televisions and remote-control navigation.
- Focus-visible controls, arrow-key navigation, Enter/select, Escape/back.
- Video player shell with graceful failure messaging.
- Initial Tizen TV manifest and media-related privileges.
- No third-party network dependency at runtime.

## Important compatibility limits
This is an initial scaffold, not yet a validated production player. Samsung TV model/year and Tizen firmware are unknown, so codec support, USB access, subtitle behavior, and installation must be tested on the target TV. Tizen web apps cannot run an Android APK. The current screen does not yet enumerate USB files or the TV content database; those integrations require testing against the TV's supported Tizen APIs and privileges. It does not yet reuse the PWA's complete library/indexing logic.

Tizen package signing is required for installation. A ZIP renamed to .wgt is not a signed, installable application. Use Tizen Studio and a Samsung certificate profile to package and sign it.

## Build and install with Tizen Studio
1. Install Tizen Studio with TV extensions and Samsung TV Extension tools.
2. Install a TV SDK compatible with your target model.
3. In Certificate Manager, create a certificate profile. For a physical TV, follow Samsung's current developer-mode/device authorization procedure and use the certificate profile required by that procedure.
4. Import tizen-tv as a Tizen Web Application project, or create a TV Web Application project and copy these files into it.
5. Confirm the tizen:application package/id fields are accepted by your installed SDK; IDs must be unique for your signing profile.
6. Build and sign the WGT in Tizen Studio, then deploy to the TV or emulator.
7. Test focus movement, Back, playback, cold start, USB/media enumeration, and every intended codec on the actual television.

## Development preview
Open index.html in a desktop browser to inspect the layout. This is not a substitute for Tizen TV testing because the tizen API is absent in a normal browser.

## Next engineering milestones
1. Confirm TV model/year and Tizen version.
2. Integrate indexed media through the supported Tizen Content API and validate USB visibility.
3. Add music queue, album/artist views, metadata and artwork.
4. Add TV-tested audio/video format handling, subtitles, resume position, and playback errors.
5. Create signed WGT packaging and a repeatable release workflow.
6. Compare behavior and design against the existing Mortimer PWA/APK without changing either target.
