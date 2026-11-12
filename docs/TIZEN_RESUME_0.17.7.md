# MUGEN 0.17.7 — TV playback resume journal

Adds per-file Tizen AVPlay resume, closed/hidden lifecycle checkpointing, and automatic seek on reopening after successful AVPlay preparation. Saved positions are bounded to 250 entries, remain in on-device localStorage, recover safely from corrupted or unavailable storage, and reset at end of media.

## Tests

- Unit tests: persistence through restart, malformed storage, quota failures, entry limits, near-end restart and stale callbacks.
- Chromium Tizen simulation: open and play, close, verify saved position and automatic resumed seek to the same position.
- CI: `npm ci && npm test && npm run build && npm run build:tizen` plus browser suites and Android Gradle checks.

**Production note:** AVPlay seek behavior depends on Samsung firmware and codecs; verify physical TV playback before distribution. This version does not claim complete PWA/Android/Tizen feature parity.
