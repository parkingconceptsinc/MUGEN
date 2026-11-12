# MUGEN TV (Tizen prototype)

This is the first TV-focused MUGEN shell. It is a separate Tizen web app so the TV build does not load the full phone/desktop PWA bundle. It currently implements remote-control navigation, USB discovery and recursive audio/video scanning through the Tizen Filesystem API, local library metadata, and Samsung AVPlay playback.

## Target status

- The manifest currently uses **Tizen TV 5.5 as a provisional minimum**. Confirm the TV's exact model code and firmware before calling this compatible or submitting it. “Crystal UHD 5700 70-inch” alone does not establish a model year.
- The app is a `.wgt` web application. It requires a Samsung certificate profile for installation. No private certificate or production signing key belongs in this repository.
- Browser preview can exercise the layout and remote keyboard. It cannot scan USB paths or play them because it has no Samsung TV APIs.
- The pre-release package identity is `MugenTV001` / `MugenTV001.MUGEN`. Preserve this identity when signing subsequent upgrades. The build validates the package ID, Samsung Product API loader and remote-key privilege before preparing the project.
- This slice supports discovering and browsing common audio/video media from mounted USB storage, visual Play/Pause and Back controls, and distinct remote media keys. Books, comics, network libraries, persistent resume positions, subtitles, playlists, and certification packaging remain planned work. AVPlay seek controls and a visible timeline have been implemented, but still require physical-TV acceptance.
- USB scanning can be cancelled and reports unreadable folders or scan limits as partial results while preserving existing library entries. Playback callbacks from an abandoned opening cannot start playback after returning to the library. Hiding the application releases the decoder; Play explicitly reopens the file when returning.
- Remote arrows follow the geometry of the rail, media list and player controls. Focus prefers the same row/column, stays at screen edges and returns to its previous library position after leaving playback. Pointer or keyboard focus is kept in sync with the remote highlight.
- Video uses an `application/avplayer` object and maps the visible stage into AVPlay's 1920-pixel coordinate system. This follows Samsung's [AVPlay guide](https://developer.samsung.com/smarttv/develop/guides/multimedia/media-playback/using-avplay.html); actual rendering remains a physical-TV acceptance check.
- During iteration, test installation may remove the previous app installation and its local data on some SDK flows. Export or recreate test library data as needed.

## Development

From the repository root:

```sh
npm ci
npm run build:tizen
```

The command creates a web project in `tizen/build/`, copies the TV manifest and icon, and uses the shared MUGEN release version. It does not sign the package. Import `tizen/build/` into Samsung Tizen Studio with the Samsung TV extensions, select the TV profile/version after confirming the device, create/select a Samsung certificate profile, and package/install the web app on the TV. Enable Developer Mode on the TV and connect it to the development computer for device testing. Final distribution through Samsung Apps TV Seller Office has a separate review and certification process.

## Device verification still required

1. Confirm the model and Tizen version in **Settings → Support → About This TV**.
2. Install a signed `.wgt`; verify launch and focus movement using only the remote.
3. Test USB mount/unmount, directory traversal, duplicate scans, persistence across app relaunch, and large folders.
4. Test representative audio/video files on this TV. The model's codec/container support is authoritative; file extensions alone are not.
5. Verify AVPlay pause/resume/seek, back navigation, and resource cleanup after leaving the player.

The prototype is not a release candidate until these checks pass on the physical target TV.
