# PWA import audit notes

## Corrected in the current working branch

- Folder scan now counts failed `getFile()` reads as individual file errors and reports an import failure as one incomplete batch. A failed partial batch no longer gets counted as if every file in that batch failed.
- A progress bar with no known total now has an indeterminate animation. The UI no longer presents a fixed 15% fill as if it measured progress. When the total is known, the bar remains determinate and is clamped to 0–100%.
- Regression coverage is in `tests/importStatus.test.mjs`.

## Dedupe risk deliberately left unchanged

The PWA stores library records in IndexedDB database `mortimer-player` at schema version 5. The `identity` index and `trackKey()` currently define file identity as path, size, and last-modified time. For a file selection without `webkitRelativePath`, the stored path is only the filename. Two files from different folders can therefore be treated as the same item if their names, sizes, and timestamps match. A changed file can also be treated as the old item when it keeps the same path, size, and timestamp.

Changing this identity rule safely is not a local comparison change. Existing users already have records and an index under the current rule; changing the key requires a defined IndexedDB schema migration and explicit handling for old collisions. A content hash would also require reading potentially large media files and would add import time and battery use. This change is deferred until the migration behavior and a non-blocking identity strategy are designed and tested against existing libraries. The current implementation therefore preserves existing records and avoids an untested migration.

Before changing dedupe, add fixtures for same-name files in separate folders, files with identical metadata but different contents, moved folders, and replacement files. The migration must preserve playback progress, playlists, favorites, and references to existing track IDs, and it must recover safely if the upgrade is interrupted.
