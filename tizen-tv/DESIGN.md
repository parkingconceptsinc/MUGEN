# Mortimer Player TV — Design Contract

## Product intent
Bring the existing Mortimer Player PWA's media-first identity to a living-room screen. This is a focused local-media player, not a streaming service dashboard. Artwork, current playback, library navigation, and remote focus are the main content.

## Reference
The PWA currently defines its dark surface system in `src/styles.css`:
- Background: `#07070a`
- Panel: `#121218`
- Raised panel: `#1a1a22`
- Divider: `#24242e`
- Primary text: `#f4f4f6`
- Muted text: `#8f8f9c`
- Accent: `#ff7849`

Use those tokens as the TV default. TV adaptation may enlarge spacing and focus states but must not change the brand accent to an unrelated green. Light mode may be added later using the PWA's settings model; do not expose a fake theme switch until implemented.

## Composition
- Landscape 16:9, safe inset: 5vw / 5vh.
- Left navigation rail with a compact Mortimer mark and destinations.
- Main region uses a current-playback hero with large album/video artwork and clear transport actions.
- Secondary region shows a short "Recently added" shelf only when real library data is connected. Preview/sample items must be labelled.
- Keep a persistent mini-player/status area when media is actually playing.
- Prefer fewer larger choices over dense grids. Avoid long scrolls and mobile-style bottom tabs.

## Typography
Use platform fonts to avoid network dependency:
- Display: `Arial, Helvetica, sans-serif`, weight 700–800; tight but readable.
- Body: same family, weight 400–600.
- TV captions: never smaller than 14px at 1920x1080 unless truly secondary metadata; scale with `clamp()`.
- Keep line lengths short and use sentence case.

## Surfaces and motion
- Background `#07070a`; panel `#121218`; raised panel `#1a1a22`; line `#24242e`.
- Orange accent `#ff7849`; text `#f4f4f6`; muted `#8f8f9c`.
- Use artwork-derived gradients only as supporting texture, not as a replacement for actual cover art.
- Radius: 8–18px according to hierarchy; do not round every element equally.
- Focus: high-contrast orange outline plus restrained outer glow; focus must remain visible on all surfaces.
- Transitions should respond to user action and stay short (120–220ms). Respect reduced motion.

## Remote interaction
- Logical directional focus within the visual layout, not an arbitrary global cycle.
- Enter/OK activates the focused item. Back/Escape closes overlays and returns to the previous screen.
- Use a visible selection/focus state that does not depend on hover.
- Minimum generous hit areas; no controls placed near screen edges.
- Avoid touch-only gestures and browser-specific file picker assumptions.

## Honesty and platform boundaries
- The current scaffold does not enumerate USB/content database items or provide complete media playback.
- Do not show fake counts, fake tracks, false progress, or imply playback is active when no media is loaded.
- Use clearly labelled preview artwork only for layout development.
- Tizen Content API and AVPlay support must be implemented and validated separately for the target firmware.
- This file is the source of truth for TV visual decisions. Update it only when the PWA's design system or explicit user requirements change.
