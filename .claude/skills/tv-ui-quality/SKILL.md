---
name: tv-ui-quality
description: Use before considering any Mortimer Player Tizen TV front-end change complete. Audit visual consistency, remote navigation, layout, runtime compatibility, media-state honesty, performance, and release readiness.
---

# TV UI quality gate

## Mandatory audit
- Read `tizen-tv/DESIGN.md`, `tizen-tv/README.md`, and current code.
- Inspect the diff and test that every visible control has a real action or an honest disabled/not-connected state.
- Validate responsive layout at 1920x1080, 1280x720, and a short-height viewport.
- Test directional focus across each grid/row/rail, including boundaries, wrap policy, hidden elements, modal entry/exit, and Back/Escape.
- Check keyboard operation with a desktop browser, then distinguish this from physical TV validation.
- Check startup with network disabled; the initial shell must render without remote fonts, images, analytics, or APIs.
- Review accessibility: semantic buttons, accessible names, status announcements, contrast, reduced-motion behavior, and legible type from a distance.
- Review performance: avoid layout thrash, large synchronous work, continuous animation, excessive DOM, and loading all media art at once.
- Validate `config.xml` syntax and compare required privileges to actual APIs used. Request only necessary privileges.
- Never commit certificates, private keys, credentials, signed packages, or generated media files.

## Evidence report
Update `tizen-tv/README.md` with:
- what was inspected;
- commands or checks actually run;
- pass/fail/not-run per check;
- what requires Tizen Studio, the TV emulator, or physical hardware;
- remaining known gaps.

## Release blockers
Do not call the work production-ready if any of these remain:
- no valid Tizen SDK build/signing evidence;
- media source enumeration is stubbed;
- playback not verified on the actual target model;
- remote focus can get lost;
- core screens contain placeholder controls without disclosure;
- errors are swallowed without a visible message.
