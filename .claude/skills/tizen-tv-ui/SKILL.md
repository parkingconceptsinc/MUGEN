---
name: tizen-tv-ui
description: Use for every visual, navigation, accessibility, layout, remote-control, or interaction change in Mortimer Player's Samsung Tizen TV app. Build a high-quality 10-foot UI for TV remotes, not a stretched mobile page.
---

# Tizen TV UI

## Design contract
- Read `tizen-tv/DESIGN.md` before implementation and preserve the Mortimer Player PWA identity.
- Design for 16:9 TV at 1920x1080 logical pixels, while remaining usable at 1280x720 and in resized desktop previews.
- Use a calm media-first composition: a clear current destination, large artwork, readable titles, restrained metadata, strong focus and a limited number of choices per screen.
- Use a consistent spacing scale, typography scale, focus treatment, surfaces, and transitions. Avoid decorative elements that compete with artwork or content.
- Respect TV overscan: keep essential controls and text inside a safe inset of at least 5% horizontally and 5% vertically.
- Use CSS layout primitives (grid/flex) and minmax/clamp carefully. Avoid absolute positioning for primary content structure.
- Keep animation short, purposeful, and reduced when `prefers-reduced-motion` is set. Do not animate every card independently.

## Remote navigation
- Support arrow keys, Enter/OK, Escape/Backspace, and common Samsung Back key behavior.
- Use a directional focus map based on layout groups; don't merely cycle through every button in DOM order.
- Every interactive element must show a clear focus state without relying on hover.
- Do not trap focus in hidden panels. On opening a dialog/player, move focus inside; on close, restore focus to the invoking control.
- Ignore repeated key events where necessary to avoid overshooting. Keep scrolling and focus movement synchronized.
- Register media keys only when the Tizen API exists; guard all device-specific APIs and keep desktop preview usable.

## Screens and states
- Include Home, Music, Video, Sources, Now Playing/Playback, and Settings destinations only to the extent implemented. For books, comics, photos, queue, and equalizer, show an explicit not-yet-connected state until real functionality exists.
- Empty state: explain that media indexing is not connected and what will enable it. Never imply that USB has been scanned if it has not.
- Unsupported/error state: identify the action and a realistic next step.
- Keep player controls and source lists accessible from the remote, with large hit targets (prefer 48 CSS px or larger in a desktop-equivalent preview; use generous TV-scaled spacing).

## Platform safeguards
- Validate APIs against Samsung's official TV API reference for the target Tizen version.
- The TV content database and AVPlay are separate integrations. Do not claim HTML video supports all codecs or that browser file APIs can enumerate USB.
- Keep the app functional as a static local preview without a Tizen runtime; clearly report which integrations require a TV.
- Never claim a signed WGT, installability, physical-device compatibility, or playback validation until tested.

## Done means
1. Review at 1920x1080 and 1280x720.
2. Exercise every arrow direction, OK, Back, media key, and focus restoration.
3. Confirm no text clipping, overlap, unreachable button, console exception, or mandatory remote asset.
4. Compare visual hierarchy and color against `tizen-tv/DESIGN.md` and the PWA.
