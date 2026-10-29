# Audio Lab and device scan — 0.13.0

The native interface remains in English. Open **Audio Lab** from the navigation rail or the shortcut in Now Playing.

## Playback mix

**Mix** provides ten EQ bands, the calculated response curve, a real FFT spectrum, peak/RMS and gain-reduction meters, preamp, automatic headroom, balance and stereo width. Drag a band or use its accessibility progress action; tap its value to reset it. Store A/B snapshots to compare settings. Editing a playback control enables processing; the master switch bypasses it. Disabled processing preserves PCM bytes, including fragmented input frames.

**Effects** includes high/low-pass filters, compressor, gate, a sample-peak limiter, tempo-synced ping-pong delay, room reverb, chorus, saturation, bit/sample-rate reduction, tremolo and ring modulation. The limiter bounds sample peaks; it is not a true-peak mastering meter. Positive EQ can still distort if headroom and limiting are disabled.

The software chain runs on decoded PCM16 (8–192 kHz, one to eight channels). Playback disables float output, encoded passthrough and offload so Media3 actually runs the processors. Pitch processing applies to mono/stereo and stereo width to stereo. Existing playback speed, queues and Android Auto remain in the playback service. The standalone video editor's MP4 export does not bake in the playback mix.

**Presets** stores complete effects settings separately for playback and voice. Existing EQ settings and named curves migrate without deleting the old preferences. There are up to 32 custom presets; A/B and reset/undo operate on the playback mix.

## Recorded voice

In **Voice**, tap **Record voice** and grant microphone access. Tap Stop to finish. Recording stops when the app leaves the foreground or reaches five minutes. Originals are private local PCM16 WAV files. At ten saved takes, export or explicitly delete one before recording another; older takes are never silently removed.

Choose key, scale, correction strength and retune time, and optionally transpose ±12 semitones. Compare **Play original** with **Play effects**, then **Render processed WAV** and choose a destination. Rendering creates a separate file and can append a two-second effect tail. Without a tail, the frame count is preserved. Voice effects and presets are separate from the playback mix; the copy button explicitly transfers playback effects.

Pitch detection/correction targets one recorded voice or solo instrument. It does not isolate singing from a mixed song, preserve formants independently, generate missing vocals, or provide a commercial Auto-Tune implementation. Use a clean monophonic take and select the intended scale. The time-domain shifter introduces a short processing delay and may color the sound at extreme settings. There is no live microphone monitor or background microphone service.

## Synthesizer

**Synth** has eight voices, two oscillators, sine/saw/square/triangle shapes, detune, blend, ADSR, resonant low-pass filtering, vibrato and output gain. Hold keyboard keys to play. The 16-step sequencer has editable notes and gates, 40–240 BPM and one-, two-, four- or eight-bar WAV rendering. Playback effects can process the synth output. Stop all releases held notes; leaving the foreground stops audio. Rendering snapshots settings and supports cancellation.

## Scan device

Use **Scan device** in Music, Videos, Books, Comics or Settings → Library. Android 11+ asks for **All files access** in its own settings; older supported Android versions use shared-storage read permission. After granting access, the scan covers shared storage and mounted SD/USB volumes. Android private app storage and Android/data and Android/obb remain excluded. This permission is optional; existing folder selection continues to work.

The scan recognizes supported audio/video extensions, EPUB, PDF, TXT/MD, CBZ/CBR/CB7, and ZIP files recognized as EPUB or image-only comics. Photos continue through Photos. Review counts, select categories and tap **Import … files**. Scanning alone does not change the library. Canonical file identities deduplicate existing SAF imports while keeping their saved kind and progress. Imports merge against the latest persisted library.

Cancel leaves the library untouched. Scans report unreadable folders and partial results, with limits of 250,000 entries, 25,000 new items and depth 64. Archives are inspected with bounds. Only explicitly imported files become library entries. Revoking access leaves entries and progress intact and displays **Restore access**. FileProvider grants cover shared storage only; app-private files are outside its roots.

## Verification

- 134 unit tests cover DSP response, bypass, fragmented frames, sanitization, dynamics, delay/reverb, mono/stereo/multichannel bounds, pitch accuracy and correction, FFT bins, presets, WAV rendering/cancellation, synth duration/envelopes and scanner deduplication/cancellation.
- Four instrumented tests use synthetic media and owned storage fixtures: actual silent Media3 output through the processor; corrected WAV and synth export with a real-time DSP benchmark; readable scanner URIs and private-file rejection; and isolated UI interaction on 1280×800 and 420×900 virtual displays.
- UI tests keep settings and the library unchanged, mute only their own players and leave the physical display alone. They neither record the user's microphone nor grant all-files permission or enumerate personal storage.
- On the SM-X900, the demanding chain processed two seconds of audio in about 0.50 seconds, with a 10 ms buffer processing p95 near 2.5 ms in the initial measurement. Performance varies with the device and enabled effects.

The DSP is independently implemented using RBJ biquad equations ([W3C cookbook](https://www.w3.org/TR/audio-eq-cookbook/)) and normalized-difference pitch detection inspired by [YIN](https://audition.ens.fr/adc/pdf/2002_JASA_YIN.pdf). Media3's PCM-only processing behavior is documented in its [DefaultAudioSink source](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java).
