# Samsung TV validation checklist

Use this checklist after importing `tizen-tv/` into a compatible Samsung TV project in Tizen Studio. Do not mark hardware-specific items as passed based on desktop browser testing.

## Device and package
- [ ] Record the exact model code from Settings → Support → About This TV.
- [ ] Record firmware and detected Tizen version.
- [ ] Confirm SDK, TV extension and `required_version` compatibility.
- [ ] Confirm the manifest is accepted by Tizen Studio.
- [ ] Create/authorize the Samsung certificate profile and build a signed WGT.
- [ ] Install on the emulator and then on the physical TV.

## Launch and layout
- [ ] Cold launch and restart without network access.
- [ ] Check 1920×1080 and 1280×720/smaller viewport behavior.
- [ ] Confirm safe screen margins, readable type and no clipped controls.
- [ ] Navigate every visible control with D-pad and select with OK/Enter.
- [ ] Verify Back/Escape behavior, focus restoration and player dismissal.
- [ ] Confirm the app does not lose focus after scanning sources or opening playback.

## USB and folder handling
- [ ] Run with no USB connected: clear empty state, no crash.
- [ ] Connect one USB: refresh discovers a mounted external storage.
- [ ] Connect more than one USB (if available): each mounted drive appears.
- [ ] Open nested directories; return with Back through each level.
- [ ] Check names with spaces, punctuation and non-ASCII characters.
- [ ] Test empty folders, folders containing only unsupported formats, and large folders.
- [ ] Remove a USB during browsing and verify errors are visible and the app remains responsive.
- [ ] Run Music and Videos scan; verify bounded-scan status and that counts match displayed candidate files.

## Playback
- [ ] Try representative files from each intended audio/video container and codec.
- [ ] Confirm play, pause/resume, stop, Back, rewind/forward and seek behavior.
- [ ] Test end-of-file, corrupt/unsupported media and file removal during playback.
- [ ] Verify the video display rectangle is correct and controls remain visible.
- [ ] Check whether audio continues or stops exactly as expected when the overlay closes.
- [ ] Verify subtitle availability and behavior separately; do not assume support from file extension alone.
- [ ] Repeat after a cold launch and app re-entry.

## Results
For each run, record date, model code, firmware/Tizen version, WGT version, USB filesystem, test file names, results and logs. Keep unsupported codecs as explicit failures; do not mark the entire format family supported from one passing sample.
