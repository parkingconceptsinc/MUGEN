// AVPlay preparation is asynchronous. Every callback belongs to one opening;
// leaving the player must invalidate it before releasing the native decoder.
export function createPlaybackController(avplay, onChange) {
  let generation = 0;
  let status = "idle";
  let intendedPlaying = true;
  const publish = (next, message) => {
    status = next;
    onChange({ status, message });
  };
  function release() {
    generation++;
    if (avplay) {
      try { avplay.stop(); } catch {}
      try { avplay.close(); } catch {}
    }
    status = "idle";
  }
  function open(path, rect, resumeAtMs = 0) {
    release();
    intendedPlaying = true;
    const opening = generation;
    const current = () => opening === generation;
    const fail = (error) => {
      if (!current()) return;
      release();
      publish("error", "Playback failed. Check the USB connection and this TV's supported formats. " + String(error && error.message || error || ""));
    };
    if (!avplay) {
      publish("unavailable", "USB playback is available on Samsung TV. Browser preview has no AVPlay decoder.");
      return;
    }
    publish("loading", "Opening media…");
    try {
      avplay.open(path);
      avplay.setListener({
        onstreamcompleted() {
          if (!current()) return;
          release();
          publish("ended", "Playback complete. Choose Play to replay, or Back to library.");
        },
        onerror: fail,
      });
      avplay.setDisplayRect(rect.x, rect.y, rect.width, rect.height);
      avplay.setDisplayMethod("PLAYER_DISPLAY_MODE_LETTER_BOX");
      avplay.prepareAsync(() => {
        if (!current()) return;
        try {
          // A pause key during preparation records intent without calling AVPlay
          // in an invalid state. READY can be played later by an explicit action.
          if (intendedPlaying) avplay.play();
          publish(intendedPlaying ? "playing" : "ready", intendedPlaying ? "Playback started." : "Ready. Choose Play to start.");
          // Resume only after AVPlay preparation, and never seek blindly into the final seconds.
          // A delayed callback from a previous opening has no effect.
          if (Number.isFinite(resumeAtMs) && resumeAtMs >= 1000 && typeof avplay.seekTo === "function") {
            const duration = Number(typeof avplay.getDuration === "function" ? avplay.getDuration() : 0);
            const resume = Math.floor(resumeAtMs);
            if (Number.isFinite(duration) && duration > 0 && resume >= 1000 && resume < duration - 3000) {
              avplay.seekTo(resume,
                () => { if (current()) onChange({ status, message: "Resumed previous position." }); },
                () => { if (current()) publish(status, "Playback started. Automatic resume is unavailable for this file."); });
            }
          }
        } catch (error) { fail(error); }
      }, fail);
    } catch (error) { fail(error); }
  }
  function setPlaying(value) {
    intendedPlaying = value;
    if (status === "loading") return;
    if (!avplay) return;
    try {
      if (value && (status === "paused" || status === "ready")) {
        avplay.play();
        publish("playing", "Playback resumed.");
      } else if (!value && status === "playing") {
        avplay.pause();
        publish("paused", "Playback paused.");
      }
    } catch (error) {
      publish(status, "Playback control failed: " + String(error && error.message || error));
    }
  }
  // AVPlay supports seeking only after the decoder has reached READY.
  // A stale async seek callback must never mutate a subsequent playback session.
  function getTimeline() {
    if (!avplay || !["ready", "playing", "paused"].includes(status)) return { position: 0, duration: 0 };
    try {
      const duration = Number(avplay.getDuration());
      const position = Number(avplay.getCurrentTime());
      return { position: Number.isFinite(position) ? Math.max(0, position) : 0,
        duration: Number.isFinite(duration) ? Math.max(0, duration) : 0 };
    } catch { return { position: 0, duration: 0 }; }
  }
  function seekBy(deltaMs) {
    if (!avplay || !["ready", "playing", "paused"].includes(status) || !Number.isFinite(deltaMs)) return false;
    const opening = generation;
    const timeline = getTimeline();
    const next = Math.max(0, Math.min(timeline.duration || Number.MAX_SAFE_INTEGER, timeline.position + deltaMs));
    try {
      avplay.seekTo(Math.round(next), () => { if (opening === generation) onChange({ status, message: "Seek complete." }); },
        error => { if (opening === generation) publish(status, "Seek failed: " + String(error && error.message || error)); });
      return true;
    } catch (error) {
      if (opening === generation) publish(status, "Seek failed: " + String(error && error.message || error));
      return false;
    }
  }
  return { open, close: release, setPlaying, seekBy, getTimeline, getStatus: () => status,
    toggle: () => setPlaying(status === "loading" ? !intendedPlaying : status !== "playing") };
}
