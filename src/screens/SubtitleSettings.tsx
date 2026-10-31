import { useState } from "react";
import { usePlayer, useProgress } from "../context";
import { calibrateSubtitles, type SubtitleReference } from "../subtitles";

export function SubtitleSettings({ onLoad }: { onLoad: () => void }) {
  const { subtitles, actions } = usePlayer();
  const { currentTime } = useProgress();
  const [sourceSeconds, setSourceSeconds] = useState(0);
  const [first, setFirst] = useState<SubtitleReference | null>(null);
  return <div>
    <div className="buttonRow"><button className="btn" onClick={onLoad}>Load SRT / VTT</button>
      {subtitles && <button className="btn" onClick={actions.clearSubtitles}>Remove subtitles</button>}</div>
    {subtitles && <>
      <p className="hint">{subtitles.name} · Positive offset makes subtitles appear later.</p>
      <label className="sheetRow">Offset (milliseconds)
        <input type="number" min={-86400000} max={86400000} step={100} value={subtitles.sync.offsetMs}
          onChange={(e) => { if (e.target.value !== "") actions.setSubtitleSync({ ...subtitles.sync, offsetMs: e.target.valueAsNumber }); }} /></label>
      <div className="buttonRow">{[-500, -100, 100, 500].map((ms) => <button key={ms} className="btn small" onClick={() => actions.setSubtitleSync({ ...subtitles.sync, offsetMs: subtitles.sync.offsetMs + ms })}>{ms > 0 ? "+" : ""}{ms} ms</button>)}</div>
      <label className="sheetRow">Timing scale
        <input type="number" min={0.5} max={2} step={0.001} value={subtitles.sync.scale}
          onChange={(e) => { if (e.target.value !== "") actions.setSubtitleSync({ ...subtitles.sync, scale: e.target.valueAsNumber }); }} /></label>
      <h3>Match a phrase to the video</h3>
      <p className="hint">Pause when a phrase is spoken. Enter its original subtitle time in seconds, then capture a reference. A second, later phrase also corrects drift.</p>
      <label className="sheetRow">Original phrase time (seconds)<input type="number" min={0} step={0.001} value={sourceSeconds} onChange={(e) => setSourceSeconds(e.target.valueAsNumber)} /></label>
      <p className="hint">Current video time: {currentTime.toFixed(3)} s{first ? ` · First reference: ${(first.subtitleMs / 1000).toFixed(3)} → ${(first.videoMs / 1000).toFixed(3)} s` : ""}</p>
      <div className="buttonRow"><button className="btn" onClick={() => {
        try {
          const ref = { subtitleMs: sourceSeconds * 1000, videoMs: currentTime * 1000 };
          const sync = calibrateSubtitles(ref);
          setFirst(ref); actions.setSubtitleSync(sync);
        } catch (error) { actions.toast((error as Error).message); }
      }}>Capture first reference</button>
      <button className="btn" disabled={!first} onClick={() => {
        if (!first) return;
        try { actions.setSubtitleSync(calibrateSubtitles(first, { subtitleMs: sourceSeconds * 1000, videoMs: currentTime * 1000 })); }
        catch (error) { actions.toast((error as Error).message); }
      }}>Capture second reference</button></div>
      <div className="buttonRow"><button className="btn" onClick={() => { setFirst(null); actions.setSubtitleSync({ offsetMs: 0, scale: 1 }); }}>Reset timing</button>
        <button className="btn primary" onClick={actions.exportSubtitles}>Export synced SRT</button></div>
    </>}
  </div>;
}
