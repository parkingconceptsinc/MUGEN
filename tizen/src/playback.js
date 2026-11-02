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
  function open(path, rect) {
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
  return { open, close: release, setPlaying, getStatus: () => status,
    toggle: () => setPlaying(status === "loading" ? !intendedPlaying : status !== "playing") };
}
