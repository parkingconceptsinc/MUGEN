---
name: mortimer-pwa-parity
description: Use whenever implementing, reviewing, or adapting Mortimer Player for Samsung Tizen TV. Preserve the established Mortimer PWA's identity, feature vocabulary, interaction intent, and media-player hierarchy while adapting rather than blindly copying mobile layouts.
---

# Mortimer PWA parity

## Required first steps
1. Inspect the current PWA on the base branch before changing TV UI. Read `src/styles.css`, `src/Player.tsx`, and relevant screen components, especially `src/screens/NowPlaying.tsx`, `src/screens/Library.tsx`, `src/screens/Queue.tsx`, `src/screens/Equalizer.tsx`, and `src/screens/Settings.tsx`.
2. Treat the existing PWA as the source of truth for product terminology, color tokens, hierarchy, and behavior. The current dark theme uses near-black surfaces and a warm orange accent (`#ff7849`); do not invent a disconnected lime-green brand.
3. Maintain a feature-parity matrix. Mark each item as implemented, adapted for TV, blocked by platform/API, or not started. Never label a stub as implemented.

## Design translation rules
- Preserve the PWA's dark, media-first visual identity, orange accent, media artwork prominence, readable metadata, and direct control labels.
- Translate mobile gestures into explicit remote-friendly controls. Do not copy touch-only swipe interactions as the only way to navigate.
- Keep the Now Playing experience first-class: artwork/video canvas, title and artist, transport controls, progress, queue access, shuffle/repeat where supported, and clear focus.
- Map Music, Videos, Books, Comics, Photos, Now Playing, Queue, Equalizer, and Settings to clear TV destinations only when their UI exists; hide or label unfinished destinations rather than pretending they work.
- Do not add fabricated library counts, track names, media availability, playback state, or success messages. Demo content must be unmistakably marked as sample content.
- Use local assets and CSS artwork where possible. The installed app must not depend on external image/font CDNs to render its core UI.

## Technical constraints
- Keep `tizen-tv/` standalone from the Vite/React PWA and Android APK unless a deliberate build integration is implemented and tested.
- Prefer HTML/CSS/vanilla JavaScript and APIs supported by the target Tizen TV runtime. Avoid modern syntax/features unless validated against the target firmware.
- Do not assume the PWA's browser storage, File System Access API, media decoders, FFmpeg/WASM, or codecs are available on Tizen.
- Separate visual UI from device services. Mock/demo adapters must be clearly labelled and replaceable by a real Tizen Content API/AVPlay adapter.
- Preserve offline launch; no mandatory network calls at startup.

## Acceptance criteria
- Same brand identity and terminology as PWA, adapted to a 10-foot screen.
- All visible controls perform an action or are explicitly disabled with a reason.
- No overlapping, clipped, tiny, or unreachable controls at 1920x1080 and 1280x720.
- Remote navigation has visible focus, logical directional movement, Enter/select activation, and Back/Escape handling.
- Loading, empty, unsupported, and error states give a useful next action.
- README accurately states what has and has not been tested on a physical TV.
