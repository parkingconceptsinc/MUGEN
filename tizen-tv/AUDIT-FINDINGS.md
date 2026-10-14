# MUGEN Tizen TV — Audit findings (source review)

Audit target: `feature/tizen-tv`. This is a source-level review plus a desktop virtual runtime; it does not certify Samsung firmware, physical USB permissions, codecs, or a signed WGT package.

## Confirmed inconsistencies and gaps

1. **Books and comics are advertised but not implemented as readers.** The dedicated routes in `app.js` are explicit placeholder/empty states. The central format registry recognizes EPUB/FB2/MOBI/AZW3 and CBZ/CBR/CB7/CBT, while `readerState()` says an ebook or comic archive engine is required. This should be shown as a capability limitation, not described as working book/comic support.
2. **Music is a file browser, not a metadata library.** The Music route calls `scanLibrary('audio')` and renders file rows. Artist/album grouping, embedded metadata, cover extraction, playlists, and persistent index are not wired in this Tizen target.
3. **CSS cascade had contradictory Home grid definitions.** This audit found five-, four-, three-, and two-column rules competing through source order. The base/breakpoint rules have now been consolidated to one five-column TV layout, three columns at medium widths, and two at narrow widths. Verify the final cascade with the Virtual TV viewport controls.
4. **Recognized image formats exceed guaranteed browser decoding.** HEIC/AVIF/TIFF are classified as images, but the reader relies on the TV WebView's native `img` decoding. Recognition is not decoding support.
5. **EPUB, comics, office documents and archives do not have bundled render/extraction engines.** These should remain explicitly marked as recognized-only / engine-required until a compatible local engine is bundled and tested.
6. **Subtitle extension overlap:** `srt`, `vtt`, `ass`, `ssa`, and `sub` occur in both text and subtitle groups. The registry resolves these to `subtitle`; the text-reader route has now been extended to accept those subtitle types as plain text. Timed subtitle rendering is still not implemented.
7. **Virtual TV is not hardware emulation.** It can test route rendering, scanning logic against deterministic mock files, state changes and UI behavior. It cannot validate AVPlay's actual decoder, TV filesystem semantics, USB removal, remote focus behavior on Tizen, or certificate/package installation.

## Test strategy

- Static syntax + format registry assertions.
- Virtual runtime with mock Tizen filesystem and AVPlay.
- Functional flows: Home/back navigation, audio scan and play state, video scan and play state, gallery scan/mode switching/image viewer, format classification and honest engine-required state.
- Physical TV checklist remains required for USB, codec samples, remote focus, memory/performance, and signed WGT installation.

## Status definitions

- **Passed in virtual runtime**: behavior worked against mocks only.
- **Failed**: reproducible UI/runtime failure under mock.
- **Not tested**: requires actual Tizen APIs, real media samples, or physical TV.
