import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent, type WheelEvent as ReactWheelEvent } from "react";
import {
  CalendarDays, Camera, ChevronLeft, ChevronRight, Download, Folder, Heart, Image as ImageIcon, Info, MapPin, Pause, Play, RotateCw, Share2, Trash2, X, ZoomIn, ZoomOut,
} from "lucide-react";
import { formatSize } from "../util";
import { displayUrl, downloadPhotos, exposureLabel, rotatePhoto, thumbUrl, toggleFavorite, usePhotos, type Photo } from "../photos";

type Zoom = { z: number; x: number; y: number };
const Z0: Zoom = { z: 1, x: 0, y: 0 };
const MAX_ZOOM = 6;
const SLIDE_MS = 4000;
const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v));
const dateFmt = new Intl.DateTimeFormat(undefined, { weekday: "long", day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit" });

export function PhotoViewer({ ids, start, albumId, onClose, onShare, onDelete, onSetCover }: {
  ids: string[];
  start: number;
  albumId?: string;
  onClose: () => void;
  onShare: (p: Photo) => void;
  onDelete: (p: Photo) => Promise<boolean>;
  onSetCover?: (p: Photo) => void;
}) {
  const { photos, favorites, rotation, albums } = usePhotos();
  const byId = useMemo(() => new Map(photos.map((p) => [p.id, p])), [photos]);
  // Keep the order the viewer was opened with, minus anything deleted meanwhile.
  const list = useMemo(() => ids.map((id) => byId.get(id)).filter((p): p is Photo => !!p), [ids, byId]);
  const [index, setIndex] = useState(() => clamp(start, 0, Math.max(0, ids.length - 1)));
  const [zoom, setZoomState] = useState<Zoom>(Z0);
  const [dx, setDx] = useState(0);
  const [ui, setUi] = useState(true);
  const [info, setInfo] = useState(false);
  const [slideshow, setSlideshow] = useState(false);
  const [box, setBox] = useState({ W: 0, H: 0 });
  const stageRef = useRef<HTMLDivElement | null>(null);
  const stripRef = useRef<HTMLDivElement | null>(null);
  const zoomRef = useRef(Z0);
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const gesture = useRef<null | { kind: "pan" | "swipe"; sx: number; sy: number; x0: number; y0: number } | { kind: "pinch"; d0: number; z0: number; cx: number; cy: number; x0: number; y0: number }>(null);
  const lastTap = useRef(0);
  const tapTimer = useRef<number | undefined>(undefined);
  const moved = useRef(false);

  const i = clamp(index, 0, Math.max(0, list.length - 1));
  const photo = list[i];
  const rot = photo ? rotation[photo.id] ?? 0 : 0;
  const albumNames = photo ? albums.filter((a) => a.photoIds.includes(photo.id)).map((a) => a.name) : [];

  const setZoom = useCallback((z: Zoom) => { zoomRef.current = z; setZoomState(z); }, []);
  const go = useCallback((delta: number) => {
    setIndex((n) => clamp(n + delta, 0, list.length - 1));
    setZoom(Z0);
  }, [list.length, setZoom]);

  useEffect(() => { if (!list.length) onClose(); }, [list.length, onClose]);

  useLayoutEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const measure = () => setBox({ W: el.clientWidth, H: el.clientHeight });
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Preload the neighbours so swiping never shows a blank frame.
  useEffect(() => {
    for (const d of [1, -1, 2]) {
      const n = list[i + d];
      if (n) { const img = new Image(); img.decoding = "async"; img.src = displayUrl(n); }
    }
  }, [i, list]);

  useEffect(() => {
    const strip = stripRef.current;
    const el = strip?.querySelector<HTMLElement>(`[data-index="${i}"]`);
    el?.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
  }, [i, ui]);

  useEffect(() => {
    if (!slideshow) return;
    setUi(false);
    const t = window.setInterval(() => {
      setIndex((n) => (n + 1 >= list.length ? 0 : n + 1));
      setZoom(Z0);
    }, SLIDE_MS);
    return () => window.clearInterval(t);
  }, [slideshow, list.length, setZoom]);

  const zoomBy = useCallback((factor: number, cx = 0, cy = 0) => {
    const z0 = zoomRef.current;
    const z = clamp(z0.z * factor, 1, MAX_ZOOM);
    if (z === 1) return setZoom(Z0);
    setZoom({ z, x: cx - ((cx - z0.x) * z) / z0.z, y: cy - ((cy - z0.y) * z) / z0.z });
  }, [setZoom]);

  const remove = useCallback(async () => {
    if (!photo) return;
    setSlideshow(false);
    if (await onDelete(photo)) setZoom(Z0);
  }, [photo, onDelete, setZoom]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement | null)?.closest("input, textarea")) return;
      if (e.key === "Escape") { if (info) setInfo(false); else onClose(); }
      else if (e.key === "ArrowRight") { setSlideshow(false); go(1); }
      else if (e.key === "ArrowLeft") { setSlideshow(false); go(-1); }
      else if (e.key === "+" || e.key === "=") zoomBy(1.5);
      else if (e.key === "-") zoomBy(1 / 1.5);
      else if (e.key === " ") { e.preventDefault(); setSlideshow((s) => !s); }
      else if (e.key === "f" && photo) toggleFavorite([photo.id]);
      else if (e.key === "i") setInfo((s) => !s);
      else if (e.key === "r" && photo) rotatePhoto(photo.id);
      else if (e.key === "Delete") void remove();
      else return;
      e.stopPropagation();
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [go, info, onClose, photo, remove, zoomBy]);

  const rel = (x: number, y: number) => {
    const r = stageRef.current!.getBoundingClientRect();
    return { x: x - r.left - r.width / 2, y: y - r.top - r.height / 2 };
  };

  function onPointerDown(e: ReactPointerEvent<HTMLDivElement>) {
    if ((e.target as HTMLElement).closest("button")) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    setSlideshow(false);
    const pts = [...pointers.current.values()];
    if (pts.length === 2) {
      const c = rel((pts[0].x + pts[1].x) / 2, (pts[0].y + pts[1].y) / 2);
      gesture.current = { kind: "pinch", d0: Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y) || 1, z0: zoomRef.current.z, cx: c.x, cy: c.y, x0: zoomRef.current.x, y0: zoomRef.current.y };
      setDx(0);
    } else if (pts.length === 1) {
      moved.current = false;
      gesture.current = { kind: zoomRef.current.z > 1 ? "pan" : "swipe", sx: e.clientX, sy: e.clientY, x0: zoomRef.current.x, y0: zoomRef.current.y };
    }
  }

  function onPointerMove(e: ReactPointerEvent<HTMLDivElement>) {
    if (!pointers.current.has(e.pointerId)) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const g = gesture.current;
    if (!g) return;
    if (g.kind === "pinch") {
      const pts = [...pointers.current.values()];
      if (pts.length < 2) return;
      const d = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
      const z = clamp((g.z0 * d) / g.d0, 1, MAX_ZOOM);
      moved.current = true;
      setZoom(z === 1 ? Z0 : { z, x: g.cx - ((g.cx - g.x0) * z) / g.z0, y: g.cy - ((g.cy - g.y0) * z) / g.z0 });
      return;
    }
    const mx = e.clientX - g.sx;
    const my = e.clientY - g.sy;
    if (Math.abs(mx) + Math.abs(my) > 6) moved.current = true;
    if (g.kind === "pan") {
      const z = zoomRef.current.z;
      const limX = (box.W * (z - 1)) / 2 + 40;
      const limY = (box.H * (z - 1)) / 2 + 40;
      setZoom({ z, x: clamp(g.x0 + mx, -limX, limX), y: clamp(g.y0 + my, -limY, limY) });
    } else {
      const atEdge = (mx > 0 && i === 0) || (mx < 0 && i === list.length - 1);
      setDx(atEdge ? mx / 3 : mx);
    }
  }

  function onPointerUp(e: ReactPointerEvent<HTMLDivElement>) {
    if (!pointers.current.has(e.pointerId)) return;
    pointers.current.delete(e.pointerId);
    const g = gesture.current;
    if (pointers.current.size === 1 && g?.kind === "pinch") {
      // One finger left after a pinch keeps panning from where it is.
      const [p] = [...pointers.current.values()];
      gesture.current = { kind: "pan", sx: p.x, sy: p.y, x0: zoomRef.current.x, y0: zoomRef.current.y };
      return;
    }
    if (pointers.current.size) return;
    gesture.current = null;
    if (g?.kind === "swipe") {
      const mx = e.clientX - g.sx;
      const my = e.clientY - g.sy;
      setDx(0);
      if (Math.abs(mx) > Math.max(60, box.W * 0.15) && Math.abs(mx) > Math.abs(my)) { go(mx < 0 ? 1 : -1); return; }
      if (my > 120 && Math.abs(my) > Math.abs(mx) * 1.5) { onClose(); return; }
    }
    if (moved.current) return;
    // Tap: a second tap within 280ms toggles zoom, a single tap toggles the controls.
    const now = Date.now();
    if (now - lastTap.current < 280) {
      window.clearTimeout(tapTimer.current);
      lastTap.current = 0;
      const p = rel(e.clientX, e.clientY);
      if (zoomRef.current.z > 1) setZoom(Z0);
      else setZoom({ z: 2.5, x: p.x * (1 - 2.5), y: p.y * (1 - 2.5) });
      return;
    }
    lastTap.current = now;
    tapTimer.current = window.setTimeout(() => setUi((v) => !v), 280);
  }

  function onWheel(e: ReactWheelEvent<HTMLDivElement>) {
    const p = rel(e.clientX, e.clientY);
    zoomBy(e.deltaY < 0 ? 1.15 : 1 / 1.15, p.x, p.y);
  }

  if (!photo) return null;
  const rotated = rot % 180 !== 0;
  const fav = favorites.has(photo.id);
  const exif = photo.exif;
  const camera = [exif?.make, exif?.model?.startsWith(exif?.make ?? "\u0000") ? exif.model.slice(exif.make!.length).trim() : exif?.model].filter(Boolean).join(" ");
  const settings = [exif?.fNumber ? `ƒ/${exif.fNumber}` : "", exposureLabel(exif?.exposureTime) ?? "", exif?.focalLength ? `${Math.round(exif.focalLength)} mm` : "", exif?.iso ? `ISO ${exif.iso}` : ""].filter(Boolean).join(" · ");

  return (
    <div className={`photoViewer${ui ? "" : " bare"}${info ? " withInfo" : ""}`} role="dialog" aria-label={photo.name}>
      <div
        ref={stageRef}
        className="pvStage"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onWheel={onWheel}
      >
        <div className="pvTrack" style={{ transform: `translateX(${dx}px)`, transition: dx ? "none" : "transform .22s ease" }}>
          <img
            key={photo.id}
            className="pvImage"
            src={displayUrl(photo)}
            alt={photo.name}
            draggable={false}
            style={{
              maxWidth: rotated ? box.H : box.W,
              maxHeight: rotated ? box.W : box.H,
              transform: `translate(${zoom.x}px, ${zoom.y}px) scale(${zoom.z}) rotate(${rot}deg)`,
              transition: gesture.current ? "none" : "transform .18s ease",
              backgroundImage: `url(${thumbUrl(photo)})`,
            }}
          />
        </div>
      </div>

      <header className="pvTop">
        <button className="iconBtn" aria-label="Close" onClick={onClose}><X size={22} /></button>
        <div className="pvTitle">
          <b>{dateFmt.format(new Date(photo.takenAt))}</b>
          <small>{i + 1} of {list.length} · {photo.name}</small>
        </div>
        <div className="pvActions">
          <button className={`iconBtn${fav ? " on" : ""}`} aria-label={fav ? "Unfavorite" : "Favorite"} title="Favorite (F)" onClick={() => toggleFavorite([photo.id])}><Heart size={20} fill={fav ? "currentColor" : "none"} /></button>
          <button className="iconBtn" aria-label="Rotate" title="Rotate (R)" onClick={() => rotatePhoto(photo.id)}><RotateCw size={20} /></button>
          <button className={`iconBtn${slideshow ? " on" : ""}`} aria-label={slideshow ? "Pause slideshow" : "Slideshow"} title="Slideshow (Space)" onClick={() => setSlideshow((s) => !s)}>{slideshow ? <Pause size={20} /> : <Play size={20} />}</button>
          <button className="iconBtn" aria-label="Share" title="Share" onClick={() => onShare(photo)}><Share2 size={20} /></button>
          <button className="iconBtn" aria-label="Download" title="Download" onClick={() => downloadPhotos([photo])}><Download size={20} /></button>
          <button className={`iconBtn${info ? " on" : ""}`} aria-label="Info" title="Info (I)" onClick={() => setInfo((s) => !s)}><Info size={20} /></button>
          <button className="iconBtn" aria-label="Delete" title="Delete" onClick={() => void remove()}><Trash2 size={20} /></button>
        </div>
      </header>

      {i > 0 && <button className="pvNav prev" aria-label="Previous photo" onClick={() => go(-1)}><ChevronLeft size={30} /></button>}
      {i < list.length - 1 && <button className="pvNav next" aria-label="Next photo" onClick={() => go(1)}><ChevronRight size={30} /></button>}

      <footer className="pvBottom">
        <div className="pvZoom">
          <button className="iconBtn" aria-label="Zoom out" onClick={() => zoomBy(1 / 1.5)} disabled={zoom.z <= 1}><ZoomOut size={18} /></button>
          <span>{Math.round(zoom.z * 100)}%</span>
          <button className="iconBtn" aria-label="Zoom in" onClick={() => zoomBy(1.5)} disabled={zoom.z >= MAX_ZOOM}><ZoomIn size={18} /></button>
        </div>
        <div className="pvStrip" ref={stripRef}>
          {list.map((p, n) => (
            <button key={p.id} data-index={n} className={n === i ? "on" : ""} aria-label={p.name} onClick={() => { setIndex(n); setZoom(Z0); }}>
              <img src={thumbUrl(p)} alt="" loading="lazy" draggable={false} />
            </button>
          ))}
        </div>
      </footer>

      {info && (
        <aside className="pvInfo" aria-label="Photo details">
          <header><b>Details</b><button className="iconBtn" aria-label="Close details" onClick={() => setInfo(false)}><X size={18} /></button></header>
          <dl>
            <div><dt><ImageIcon size={17} /></dt><dd><b>{photo.name}</b><small>{photo.width} × {photo.height} · {(photo.width * photo.height / 1e6).toFixed(1)} MP · {formatSize(photo.size)}</small></dd></div>
            {(camera || settings) && <div><dt><Camera size={17} /></dt><dd><b>{camera || "Camera"}</b>{settings && <small>{settings}</small>}{exif?.lens && <small>{exif.lens}</small>}</dd></div>}
            {exif?.lat != null && exif.lon != null && (
              <div><dt><MapPin size={17} /></dt><dd>
                <b>{exif.lat.toFixed(5)}, {exif.lon.toFixed(5)}</b>
                <a href={`https://www.openstreetmap.org/?mlat=${exif.lat}&mlon=${exif.lon}#map=16/${exif.lat}/${exif.lon}`} target="_blank" rel="noreferrer">Open map</a>
              </dd></div>
            )}
            <div><dt><CalendarDays size={17} /></dt><dd><b>{dateFmt.format(new Date(photo.takenAt))}</b><small>Added {new Date(photo.addedAt).toLocaleDateString()}</small></dd></div>
            <div><dt><Folder size={17} /></dt><dd><b>{photo.folder || "No folder"}</b>{albumNames.length > 0 && <small>In {albumNames.join(", ")}</small>}</dd></div>
          </dl>
          {onSetCover && albumId && <button className="btn" onClick={() => onSetCover(photo)}>Use as album cover</button>}
        </aside>
      )}
    </div>
  );
}
