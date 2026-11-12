# MUGEN 0.17.4 — playlist recovery and TV remote navigation

Development candidate, November 6, 2026. Continues the audio/library candidate in PR #70; main remains 0.17.2 until integration. Shared version is 0.17.4, Android version code 1704. This does not imply that a signed APK or TV installer has shipped.

## PWA corrections

- Reproduced the professional review's blocker: startup mapped playlist and queue IDs through a Set. A → B → A became A → B even without aliases. Alias substitution now preserves every sequence position; favorites use an explicit unique mapping.
- Restoring a queue counts retained positions before the saved index, preserving the selected occurrence when repeated IDs converge or earlier files are unavailable. A missing selected file falls forward to the next available position, or back to the last available position. Removing other library files also preserves the selected repeated occurrence.
- Order and selected index now persist in one versioned `mortimer:playbackQueue` snapshot. Existing `queue`/`qIndex` preferences remain available for first migration and failed-write recovery. A valid new snapshot takes precedence over stale legacy preferences. Alias journals remain retryable; imported media/source files are unaffected.
- New pure regressions cover order, aliases, selected repeated occurrences, missing files, empty/malformed snapshots, migration and idempotence. The Chromium audio/library suite injects snapshot-write failure after duplicate repair, reloads with retained aliases/legacy preferences, and checks repeated playlists, original queue and exact selected position through several restarts and real queue actions.

## Tizen corrections

- Remote arrows choose controls by screen geometry, preferring the same row/column with a distance-based fallback. The previous linear DOM traversal made Left behave like Up and Right like Down.
- Focus stays at directional edges, ignores zero-size controls, follows the category selected and synchronizes pointer/keyboard focus with the remote highlight. Existing return-to-library focus and distinct media keys remain in place.
- Three new logic regressions and the existing Chromium TV simulation cover grid movement, edges, hidden controls, rail-to-list movement and row navigation.

## Validation and remaining acceptance

- Clean `npm ci`, all **75 Node tests**, PWA TypeScript/build, Tizen build and `git diff --check` pass locally.
- The local Chromium download returned a truncated archive. Browser tests and Android unit/lint/debug assembly run in the PR's GitHub Actions workflow; their exact results are recorded on the PR, not inferred from local builds.
- No Android playback or storage logic changed in this continuation. Its earlier DSP/document-URI fixes remain part of this candidate and need the same physical-device acceptance.
- Original Android signing credentials, signed upgrade testing, Samsung model/firmware and certificate, signed WGT installation, physical playback/Bluetooth/screen-off and large-library acceptance remain open.
- Tizen seek/resume/queue/subtitles, recovery exports, incremental library loading and optional offline-engine downloads remain subsequent work. General preference-write errors still need a visible recovery workflow; atomic queue snapshots preserve the last successful queue but do not make a failed write succeed.
