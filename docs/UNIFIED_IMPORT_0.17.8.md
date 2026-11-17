# MUGEN 0.17.8 — unified library import

Status: candidate on `feature/unified-import-0.17.8`, based on main 0.17.7 (`3bf99e9`). Android and browser CI validation pending. No production release has been performed.

## Behavior

`Add files` and `Add folder` accept mixed collections. The current screen no longer determines a file's type or destination. The same import actions are available from Android Home and each library section; web sections use the existing shared import queue.

| Recognized files | Destination |
| --- | --- |
| Audio | Music |
| Video | Videos |
| EPUB, PDF and supported text/documents | Books |
| Comic archives | Comics |
| Photos | Photos |

Each platform has one import classifier. Known extensions take priority over unreliable MIME hints. Extensionless files and `.bin` downloads can recover a supported type from a specific MIME hint. Explicitly unsupported extensions remain unsupported. Automatic routing does not establish codec support.

Android's picker uses all file types. Folder picks and the device scanner use the common classifier; the scanner retains its metadata check for ambiguous 3GP containers. Seekable ZIP documents are inspected without extraction and accepted only as EPUB or image-only comics. Photos imported through document access appear in Photos without requiring broad gallery permission. Existing device gallery access remains available. Imported photos receive stable IDs for favorites and viewing.

The PWA classifier is shared by import orchestration and format helpers. Media, books/comics and photos retain their existing storage and playback/readers. A mixed selection reports songs, videos, books, comics and photos separately; unsupported picked files are counted. MIME-resolved documents retain their detected reader format even when their filename has no useful extension.

## Data preservation

No bulk library rewrite or migration is introduced. Android keeps document identity, atomic commits, cancellation checks and saved reading progress. Import summaries count records accepted inside the atomic commit. PWA media/documents keep their existing content-based deduplication; manual shelf changes and metadata survive reimport.

Photo deduplication in the PWA retains the existing path, size and modification-date identity. Selecting the same photo through a different path can still create another photo entry. Existing large-library loading and photo repair are separate work.

## Validation

- 84 Node tests passed locally, including the new mixed-routing and real IndexedDB storage regressions.
- TypeScript and production PWA build passed; Tizen web project build passed; whitespace checks passed.
- Android integration tests added for mixed file/folder selections, unsupported files, MIME fallback, deduplication, preservation of reading progress, specialized Studio filtering and imported photo visibility/identity.
- Chromium integration test added for mixed files from five sections, MIME fallback, unsupported files, reimport, folder paths and reload. It is included in the existing CI workflow.
- Browser execution could not start locally: Chromium's process-singleton socket was denied by the execution environment. No browser success is claimed for this candidate.
- Local Android SDK/Gradle are unavailable. Android tests, lint and APK assembly have not run for this candidate.

## Remaining release checks

Upload the candidate branch and run the existing Android/browser CI before merging or deploying. A signed production APK, an update test over the installed application, and real-device playback/photo checks remain necessary. No production release was performed.
