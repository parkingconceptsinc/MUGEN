# MUGEN 0.17.1 — library reliability and TV controls

MUGEN 0.17.1, Android version code 1701. The user explicitly authorized sending the prepared changes and integrating them into `main` through PR #69. GitHub validation gates the PWA deployment; signed Android/Tizen distributions remain separate release steps.

| Platform | Implemented | Verification / remaining acceptance |
| --- | --- | --- |
| PWA | Counts committed records only; checks cancellation before IndexedDB writes; yields metadata parsing to imports. | TypeScript and production build pass locally. CI adds real IndexedDB abort/retry/reload coverage with 305 files. Phone quota, permissions and iPhone update checks remain. |
| Android APK | SAF traversal reports visited/found counts, runs on IO, yields for cancellation, deduplicates document IDs and explicitly rejects oversized scans without committing partial data. | Four new Robolectric cases cover 305 files, repeated IDs, limits and cancellation. Gradle/lint/debug assembly run in CI; no local Gradle/SDK. Physical SD/USB and production-signature update remain. |
| Tizen | Dedicated AVPlay video surface; viewport-scaled display; stale callback invalidation; distinct Play/Pause; accessible Back button; disabled pagination excluded from focus; cancels USB scanning and reports partial failures/limits; closes decoder on hide/end/error. Adds the missing Samsung Product API loader and replaces the invalid dotted web package ID with `MugenTV001` / `MugenTV001.MUGEN`. | Nine new Node regression cases pass locally; web project builds and rejects invalid package/loader/input privilege. CI adds DOM/remote-key simulation. A simulation does not verify Samsung hardware APIs or codec output. Model/firmware, certificate and physical-TV installation remain required. |

Local validation: 56 Node tests passing, PWA production build, Tizen build, JavaScript syntax, XML parsing and `git diff --check`. New Android and browser integration checks are authored but not yet executed. Local Gradle/SDK are unavailable; Chromium download did not produce a valid archive; GitHub Actions must verify this revision before its production deployment.

SAF safety limits are 250,000 visited entries, 25,000 compatible files and 64 directory levels. USB TV scans retain at most 15,000 new results per scan and report truncation explicitly. These bounds do not delete or truncate the existing saved library.

Tizen reference implementation follows Samsung's [AVPlay guide](https://developer.samsung.com/smarttv/develop/guides/multimedia/media-playback/using-avplay.html), [AVPlay API](https://developer.samsung.com/smarttv/develop/api-references/samsung-product-api-references/avplay-api.html), and [remote-control guide](https://developer.samsung.com/smarttv/develop/guides/user-interaction/remote-control.html).

Next priorities: validate these checks in CI, run the same import/recovery cases on actual phones, add Tizen seek/resume/queue, validate USB removal/reconnection on the TV, and complete the safe PWA identity-index migration design. This delivery does not establish full parity or a production-ready TV release.

## CI follow-up — 0.17.2

The browser integration test now targets the multiple-media input instead of the subtitle picker. Android compilation succeeded, but 19 Robolectric cases could not start because repo1.maven.org returned HTTP 429 while fetching the Android 9 runtime JAR. Test JVMs now use Maven Central at repo.maven.apache.org. No tests are skipped. Shared release increments to 0.17.2 / Android code 1702; full CI and publication must confirm the result.
