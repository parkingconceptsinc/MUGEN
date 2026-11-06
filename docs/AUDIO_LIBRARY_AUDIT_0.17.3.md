# MUGEN 0.17.3 — audio continuity and duplicate library records

Reported symptoms: audio intermittently cuts/stops and the library grows duplicate/triplicate entries. Baseline: main 415fd8b, 0.17.2. Device reproduction of the user's exact files is still pending.

## Confirmed code defects and changes

- PWA identity used relative path, size and modification time. Different picker roots/timestamps imported the same named file repeatedly; homonyms sharing those fields could be discarded without comparing contents. Media/books now use a name+size candidate index, then compare complete Blob contents in 1 MiB chunks. Changed contents remain separate. Concurrent write transactions recheck candidate IDs and retry if another tab imported first.
- IndexedDB v6 adds a candidate index and persistent ID aliases without rewriting media during schema upgrade. Startup only consolidates byte-identical named records. The merged record, deleted copies and alias journal commit in one transaction. Favorites, playlists, queue/current item, original queue, resume, play counts and reading/video history resolve old IDs on load. Interrupted cleanup is atomic; retained aliases make interrupted preference remapping retryable. No source files are modified.
- PWA startup tag refresh bypassed the import metadata queue. Two tag parsers, video thumbnails and book covers could run alongside playback. Startup/import enrichment now shares a serial lane that pauses for playback, imports and hidden pages. Tag parsing runs in a terminable module worker with a 15-second timeout. Starting playback aborts the thumbnail decoder; new covers wait while playing. Native media playback stays in use with effects disabled.
- A rejected older play request could change the current playing state after a track switch. Request generations now ignore stale rejections. Returning to a visible page resumes an already-playing Web Audio context without forcing a paused element to play. Web Audio uses the playback latency hint.
- APK default bypass still ran PCM conversion, DSP state and spectrum analysis before discarding the result. Disabled processing now passes complete PCM frames through unchanged and allocates DSP only when enabled. Disabled pitch grains skip their expensive interpolation while retaining delay history.
- APK library identity used the entire content URI. Individual document and tree-scoped picks of the same provider document now share authority+document-ID identity. Existing progress uses the latest checkpoint while retaining the original playable URI; progress lookups accept either wrapper. Different documents/providers remain distinct. This does not merge unrelated MediaStore/file/provider IDs based only on names.

## Validation

- 66 Node tests pass locally, including eight IndexedDB/content migration regressions and two background scheduling regressions.
- PWA TypeScript/build and Tizen build pass locally; shared version 0.17.3 / Android code 1703.
- Added browser regression: migrate triplicates and saved references, preserve different same-name content, reimport once, ignore an old rejected play request, keep audio progressing during import/navigation, and resume worker tags after pausing.
- Added four Android tests for bypass/reenabling and document-URI aliases/progress/homonyms.
- Local Chromium download returned a truncated archive; local Gradle/SDK are unavailable. Browser and Android checks must pass in Actions before Pages deployment.
- Automated browser continuity checks measure playback clock/resource/UI state, not audible output on the user's phone. Physical device, Bluetooth/headset, screen-off, codec and very large library checks remain required.

## Limits

Exact duplicate checks read full candidate contents; initial repair/reimport of a large set of repeated videos can take time. Files with different names are deliberately separate, even when their bytes match. Photos retain their existing identity path in this change. A PWA cannot guarantee indefinite background playback when a mobile OS suspends the browser. APK production distribution still requires the original signing credentials; debug builds do not replace that signature.
