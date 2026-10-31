# Formatos portables MUGEN 0.16.0

Son formatos de intercambio explícitos, independientes del JSON interno de cada app. Sus importadores rechazan versiones desconocidas antes de aplicar cambios.

## Studio: `.mugenstudio`

ZIP STORE sin compresión, cifrado, descriptores de datos, enlaces ni ZIP64. Contiene únicamente `manifest.json` y `media/source-000.bin`, `media/source-001.bin`, etc. Cada payload debe coincidir con su tamaño y CRC32. El manifiesto admite como máximo 1 MiB; el conjunto, 32 fuentes/clips, 384 MiB de medios y 30 minutos de montaje. Las rutas nunca proceden del nombre de un archivo del usuario.

El manifiesto tiene exactamente `format: "mugen-studio"`, `version: 1`, `project` y `media`.

- `project`: `id` UUID canónico en minúsculas, `name`, `aspect` (source, 16:9, 9:16, 1:1), `height` (720 o 1080), `clips`.
- Cada clip: `id`, `mediaId`, `in`, `out`, `speed`, `volume`, `mute`, `rotation`, `flip`, `brightness`, `contrast`, `saturation`.
- Cada medio: `id`, `path`, `name`, `duration`, `width`, `height`, `size`, `type`.

Tiempos en segundos; speed 0.25–4; al menos 0.5 segundos de fuente por clip; volume 0–2; brightness −1–1; contrast/saturation factores 0–2 con valor neutral 1. La transformación canónica gira en sentido horario y después refleja horizontalmente en el lienzo. Todos los medios deben estar referenciados. No se admiten campos de efectos adicionales que luego pudieran perderse.

Android adapta contraste mediante `(factor−1)/(factor+1)` y saturación mediante `100×(factor−1)`; su orden de transformación también se adapta. Rechaza el volumen de clip superior a 1 y los ajustes nativos que excedan el rango portable. La importación verifica metadatos reales antes de registrar un proyecto y usa IDs nuevos para conservar proyectos existentes. La PWA sustituye su espacio de trabajo solo tras confirmar y mediante una transacción atómica.

Las pruebas de ambos lectores comparten `shared/fixtures/studio-reference.mugenstudio`, un MP4 sintético de cuatro segundos y un manifiesto con recorte, velocidad, color, giro y espejo.

## Sintetizador: JSON

`{ "format": "mugen-synth", "version": 1, "settings": { ... } }`, hasta 16 KiB. Los ajustes usan `wave`, `octave`, `attack`, `decay`, `sustain`, `release`, `cutoff`, `resonance`, `detuneCents`, `blend`, `vibratoHz`, `vibratoDepth`, `volume`, `bpm`, `sequence` y `steps`.

ADSR en segundos, cutoff en Hz, detune en cents, vibratoDepth en semitonos; las 16 notas son MIDI 24–108 o −1 como silencio. `sequence` siempre se importa detenido. El archivo no incluye los efectos de reproducción ni un render WAV. `shared/fixtures/synth-patch.json` es el contrato común probado por PWA y Android.
