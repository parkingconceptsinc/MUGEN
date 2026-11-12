---
name: frontend-design
description: Use whenever building or polishing Mortimer Player UI, especially the Samsung Tizen TV front end. Make deliberate, production-minded visual decisions grounded in the existing PWA instead of generic templates, and critique the implementation before calling it done.
---

# Frontend design for Mortimer Player

## Before coding
1. Read `tizen-tv/DESIGN.md` and inspect the relevant PWA screen and `src/styles.css`.
2. State a short design hypothesis internally: audience, primary task, content hierarchy, palette, typography, layout, and one distinctive visual signature.
3. Preserve the established Mortimer identity. The PWA's dark surfaces and orange `#ff7849` accent are the reference. Do not switch to unrelated accent colors or invent a second brand.
4. Prefer media artwork and purposeful negative space over ornamental gradients, piles of equal cards, repeated eyebrow labels, or generic dashboard layouts.

## Build
- Make Now Playing and library navigation the product's visual center of gravity.
- Use large, readable typography and deliberate contrast. Avoid low-contrast captions and tiny metadata on a television.
- Use a limited token system for colors, spacing, radii, borders, focus, and motion.
- Use animation only to communicate selection, navigation, or a real state change. Respect reduced-motion preferences.
- Keep the core interface self-contained and local; external fonts, image CDNs, and remote scripts must not be required for startup.
- Design empty states as helpful guidance, not decorative placeholders. Never use invented tracks or fake playback state without an explicit sample label.
- Keep all controls consistent in naming and behavior. A button label must describe the action it actually performs.

## Critique pass
After implementation, inspect the whole screen at 1920x1080 and 1280x720. Look for hierarchy problems, accidental clutter, inconsistent spacing, focus ambiguity, text clipping, collisions, and excessive decoration. Remove elements that do not improve understanding or operation.
- Compare the result against the existing PWA, not only against the new TV page in isolation.
- Test every visible control with mouse/keyboard and directional remote navigation where possible.
- Separate verified browser behavior from behavior that still requires Tizen Studio, an emulator, or a physical TV.
- Never claim perfection, production readiness, or device compatibility without evidence.
