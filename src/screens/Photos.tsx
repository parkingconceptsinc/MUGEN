import { memo, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import {
  ArrowDownUp, Check, ChevronLeft, FilePlus, Folder, FolderOpen, FolderPlus, Heart, Images, ListChecks, Pencil, Plus, Search, Share2,
  Download, Trash2, X, ZoomIn, ZoomOut, Image as ImageIcon, MinusCircle, Star,
} from "lucide-react";
import { usePlayer } from "../context";
import { ScreenHeader, Sheet, SheetItem } from "../components";
import { usePref } from "../prefs";
import { formatSize } from "../util";
import {
  addToAlbum, createAlbum, deleteAlbum, downloadPhotos, removeFromAlbum, removePhotos, renameAlbum, setAlbumCover, sharePhotos,
  thumbUrl, toggleFavorite, usePhotos, type Photo, type PhotoAlbum,
} from "../photos";
import { PhotoViewer } from "./PhotoViewer";

type Tab = "library" | "albums" | "favorites";
type Sort = "taken-desc" | "taken-asc" | "added" | "name";
type Place = { kind: "album"; id: string } | { kind: "folder"; path: string } | null;

const SIZES = [72, 96, 124, 164, 220];
const SORTS: Array<[Sort, string]> = [["taken-desc", "Newest first"], ["taken-asc", "Oldest first"], ["added", "Recently added"], ["name", "File name"]];
const dayFmt = new Intl.DateTimeFormat(undefined, { weekday: "short", day: "numeric", month: "long", year: "numeric" });
const monthFmt = new Intl.DateTimeFormat(undefined, { month: "long", year: "numeric" });

function sortPhotos(list: Photo[], sort: Sort) {
  const out = [...list];
  if (sort === "taken-desc") out.sort((a, b) => b.takenAt - a.takenAt);
  else if (sort === "taken-asc") out.sort((a, b) => a.takenAt - b.takenAt);
  else if (sort === "added") out.sort((a, b) => b.addedAt - a.addedAt);
  else out.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" }));
  return out;
}

function groupByDay(list: Photo[], sort: Sort): Array<{ key: string; label: string; month: string; photos: Photo[] }> {
  if (sort === "name" || sort === "added") return [{ key: "all", label: "", month: "", photos: list }];
  const groups: Array<{ key: string; label: string; month: string; photos: Photo[] }> = [];
  for (const p of list) {
    const d = new Date(p.takenAt);
    const key = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
    const last = groups[groups.length - 1];
    if (last?.key === key) last.photos.push(p);
    else groups.push({ key, label: dayFmt.format(d), month: monthFmt.format(d), photos: [p] });
  }
  return groups;
}

export function Photos() {
  const { actions } = usePlayer();
  const { loaded, photos, favorites, albums } = usePhotos();
  const [tab, setTab] = usePref<Tab>("photoTab", "library");
  const [sort, setSort] = usePref<Sort>("photoSort", "taken-desc");
  const [size, setSize] = usePref("photoGridSize", 2);
  const [place, setPlace] = useState<Place>(null);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<Set<string> | null>(null);
  const [pickFor, setPickFor] = useState<string | null>(null);
  const [viewer, setViewer] = useState<{ ids: string[]; index: number } | null>(null);
  const [sheet, setSheet] = useState<null | "sort" | "album-picker" | "album-menu">(null);

  const byId = useMemo(() => new Map(photos.map((p) => [p.id, p])), [photos]);
  const album = place?.kind === "album" ? albums.find((a) => a.id === place.id) : undefined;
  const q = query.trim().toLowerCase();

  const scope: Photo[] = useMemo(() => {
    let list: Photo[];
    if (place?.kind === "album") list = (album?.photoIds ?? []).map((id) => byId.get(id)).filter((p): p is Photo => !!p);
    else if (place?.kind === "folder") list = photos.filter((p) => p.folder === place.path);
    else if (tab === "favorites") list = photos.filter((p) => favorites.has(p.id));
    else list = photos;
    if (q) list = list.filter((p) => `${p.name} ${p.folder} ${p.exif?.make ?? ""} ${p.exif?.model ?? ""}`.toLowerCase().includes(q));
    return sortPhotos(list, sort);
  }, [place, album, byId, photos, tab, favorites, q, sort]);

  const showGrid = place != null || tab !== "albums" || pickFor != null;
  const groups = useMemo(() => groupByDay(scope, sort), [scope, sort]);
  const selecting = selected != null;
  const selectedList = selected ? scope.filter((p) => selected.has(p.id)) : [];

  // Leaving a view ends any selection that belonged to it.
  useEffect(() => { if (!pickFor) setSelected(null); }, [place, tab]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = (id: string) => setSelected((s) => {
    const next = new Set(s ?? []);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  });
  const open = (p: Photo) => setViewer({ ids: scope.map((x) => x.id), index: scope.indexOf(p) });
  const endSelection = () => { setSelected(null); setPickFor(null); };

  const title = place?.kind === "album" ? album?.name ?? "Album"
    : place?.kind === "folder" ? place.path.split("/").pop() || "Folder"
    : pickFor ? `Add to “${albums.find((a) => a.id === pickFor)?.name ?? "album"}”`
    : "Photos";
  const subtitle = photos.length ? `${scope.length} photo${scope.length === 1 ? "" : "s"}${!place && tab === "library" ? ` · ${formatSize(photos.reduce((n, p) => n + p.display.size + p.thumb.size, 0))} stored` : ""}` : undefined;

  const onBack = pickFor
    ? () => { const id = pickFor; endSelection(); setPlace({ kind: "album", id }); }
    : place ? () => setPlace(null) : undefined;

  async function share(list: Photo[]) {
    const r = await sharePhotos(list);
    if (r === "unsupported") { downloadPhotos(list); actions.toast("Sharing isn't available here, so the photos were downloaded"); }
  }

  async function remove(list: Photo[]) {
    if (!list.length) return;
    if (!window.confirm(`Remove ${list.length} photo${list.length === 1 ? "" : "s"} from Mortimer? The files on your device are not touched.`)) return;
    await removePhotos(list.map((p) => p.id));
    setSelected(null);
    actions.toast(`${list.length} photo${list.length === 1 ? "" : "s"} removed`);
  }

  function newAlbum(ids: string[]) {
    const name = window.prompt("Album name", "New album");
    if (name == null) return null;
    const created = createAlbum(name, ids);
    actions.toast(`Album “${created.name}” created`);
    return created;
  }

  return (
    <section className="screen photos">
      <ScreenHeader title={selecting && !pickFor ? `${selected!.size} selected` : title} subtitle={selecting ? undefined : subtitle} onBack={onBack}>
        {selecting ? (
          <>
            <button className="iconBtn" aria-label="Select all" title="Select all" onClick={() => setSelected(new Set(scope.map((p) => p.id)))}><ListChecks size={20} /></button>
            <button className="iconBtn" aria-label="Cancel selection" title="Cancel" onClick={endSelection}><X size={20} /></button>
          </>
        ) : (
          <>
            {showGrid && scope.length > 0 && <button className="iconBtn" aria-label="Select" title="Select" onClick={() => setSelected(new Set())}><Check size={20} /></button>}
            {showGrid && <button className="iconBtn" aria-label="Sort" title="Sort" onClick={() => setSheet("sort")}><ArrowDownUp size={19} /></button>}
            {album && <button className="iconBtn" aria-label="Album options" title="Album options" onClick={() => setSheet("album-menu")}><Pencil size={18} /></button>}
            <button className="iconBtn" aria-label="Add folder" title="Add folder" onClick={actions.importFolder}><FolderOpen size={20} /></button>
            <button className="iconBtn" aria-label="Add photos" title="Add photos" onClick={actions.importFiles}><FilePlus size={20} /></button>
          </>
        )}
      </ScreenHeader>

      {!place && !pickFor && (
        <div className="segmented photoTabs">
          <button className={tab === "library" ? "on" : ""} onClick={() => setTab("library")}><Images size={16} /> Library</button>
          <button className={tab === "albums" ? "on" : ""} onClick={() => setTab("albums")}><Folder size={16} /> Albums</button>
          <button className={tab === "favorites" ? "on" : ""} onClick={() => setTab("favorites")}><Heart size={16} /> Favorites</button>
        </div>
      )}

      {!loaded ? (
        <div className="emptyState"><Images className="spin" /><p>Loading your photos…</p></div>
      ) : !photos.length ? (
        <div className="emptyState welcome">
          <div className="photoEmptyIcon"><Images size={44} /></div>
          <p>Add a folder of photos or pick a few images. Mortimer keeps a copy on this device and builds your timeline from the date each photo was taken.</p>
          <div className="heroActions">
            <button className="btn primary" onClick={actions.importFolder}><FolderOpen size={18} /> Add photo folder</button>
            <button className="btn" onClick={actions.importFiles}><FilePlus size={18} /> Add photos</button>
          </div>
          <small>JPEG, PNG, WebP, AVIF, GIF and BMP. HEIC photos from iPhone need converting to JPEG first.</small>
        </div>
      ) : !showGrid ? (
        <AlbumsView albums={albums} photos={photos} byId={byId} onOpen={setPlace} onCreate={() => newAlbum([])} />
      ) : (
        <>
          <div className="photoTools">
            <label className="searchBox">
              <Search size={17} />
              <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search by name, folder or camera" />
              {query && <button className="iconBtn" aria-label="Clear search" onClick={() => setQuery("")}><X size={16} /></button>}
            </label>
            <div className="photoZoom">
              <button className="iconBtn" aria-label="Smaller thumbnails" disabled={size <= 0} onClick={() => setSize((s) => Math.max(0, s - 1))}><ZoomOut size={18} /></button>
              <button className="iconBtn" aria-label="Larger thumbnails" disabled={size >= SIZES.length - 1} onClick={() => setSize((s) => Math.min(SIZES.length - 1, s + 1))}><ZoomIn size={18} /></button>
            </div>
          </div>
          {!scope.length ? (
            <div className="emptyState">
              {tab === "favorites" && !place ? <><Star /><p>Tap the heart on a photo to keep it here.</p></>
                : album ? <><ImageIcon /><p>This album is empty.</p><button className="btn primary" onClick={() => { setPickFor(album.id); setPlace(null); setTab("library"); setSelected(new Set()); }}><Plus size={18} /> Add photos</button></>
                : <><Search /><p>No photos match.</p></>}
            </div>
          ) : (
            <div className="photoGroups" style={{ "--tile": `${SIZES[size] ?? 124}px` } as CSSProperties}>
              {groups.map((g, i) => (
                <section key={g.key} className="photoGroup">
                  {g.label && (
                    <header className="photoDay">
                      {(i === 0 || groups[i - 1].month !== g.month) && <h2>{g.month}</h2>}
                      <div>
                        <span>{g.label}</span>
                        {selecting && (
                          <button className="linkBtn" onClick={() => setSelected((s) => {
                            const next = new Set(s ?? []);
                            const all = g.photos.every((p) => next.has(p.id));
                            for (const p of g.photos) all ? next.delete(p.id) : next.add(p.id);
                            return next;
                          })}>{g.photos.every((p) => selected!.has(p.id)) ? "Deselect" : "Select day"}</button>
                        )}
                      </div>
                    </header>
                  )}
                  <div className="photoGrid">
                    {g.photos.map((p) => (
                      <Tile key={p.id} photo={p} favorite={favorites.has(p.id)} selecting={selecting} selected={!!selected?.has(p.id)}
                        onOpen={() => (selecting ? toggle(p.id) : open(p))} onLongPress={() => { setSelected((s) => new Set([...(s ?? []), p.id])); }} />
                    ))}
                  </div>
                </section>
              ))}
            </div>
          )}
        </>
      )}

      {selecting && (
        <div className="photoActionBar" role="toolbar" aria-label="Selection actions">
          {pickFor ? (
            <>
              <span>{selected!.size} selected</span>
              <button className="btn primary" disabled={!selected!.size} onClick={() => {
                const id = pickFor;
                addToAlbum(id, [...selected!]);
                actions.toast(`${selected!.size} photo${selected!.size === 1 ? "" : "s"} added to the album`);
                endSelection();
                setPlace({ kind: "album", id });
              }}><Plus size={18} /> Add</button>
            </>
          ) : (
            <>
              <button disabled={!selectedList.length} onClick={() => toggleFavorite(selectedList.map((p) => p.id))}><Heart size={19} /><span>Favorite</span></button>
              <button disabled={!selectedList.length} onClick={() => setSheet("album-picker")}><FolderPlus size={19} /><span>Add to album</span></button>
              <button disabled={!selectedList.length} onClick={() => void share(selectedList)}><Share2 size={19} /><span>Share</span></button>
              <button disabled={!selectedList.length} onClick={() => downloadPhotos(selectedList)}><Download size={19} /><span>Save</span></button>
              {album && <button disabled={!selectedList.length} onClick={() => { removeFromAlbum(album.id, selectedList.map((p) => p.id)); setSelected(null); }}><MinusCircle size={19} /><span>Remove</span></button>}
              <button className="danger" disabled={!selectedList.length} onClick={() => void remove(selectedList)}><Trash2 size={19} /><span>Delete</span></button>
            </>
          )}
        </div>
      )}

      {sheet === "sort" && (
        <Sheet title="Sort photos" onClose={() => setSheet(null)}>
          {SORTS.map(([id, label]) => (
            <SheetItem key={id} icon={sort === id ? Check : ArrowDownUp} label={label} onClick={() => { setSort(id); setSheet(null); }} />
          ))}
        </Sheet>
      )}
      {sheet === "album-picker" && (
        <Sheet title="Add to album" subtitle={`${selectedList.length} photo${selectedList.length === 1 ? "" : "s"}`} onClose={() => setSheet(null)}>
          <SheetItem icon={Plus} label="New album…" onClick={() => { if (newAlbum(selectedList.map((p) => p.id))) { setSheet(null); setSelected(null); } }} />
          {albums.map((a) => (
            <SheetItem key={a.id} icon={Folder} label={`${a.name} (${a.photoIds.length})`} onClick={() => {
              addToAlbum(a.id, selectedList.map((p) => p.id));
              actions.toast(`Added to “${a.name}”`);
              setSheet(null);
              setSelected(null);
            }} />
          ))}
        </Sheet>
      )}
      {sheet === "album-menu" && album && (
        <Sheet title={album.name} subtitle={`${album.photoIds.length} photos`} onClose={() => setSheet(null)}>
          <SheetItem icon={Plus} label="Add photos" onClick={() => { setSheet(null); setPickFor(album.id); setPlace(null); setTab("library"); setSelected(new Set()); }} />
          <SheetItem icon={Pencil} label="Rename album" onClick={() => { const name = window.prompt("Album name", album.name); if (name != null) renameAlbum(album.id, name); setSheet(null); }} />
          <SheetItem icon={Trash2} label="Delete album" danger onClick={() => {
            if (!window.confirm(`Delete the album “${album.name}”? The photos stay in your library.`)) return;
            deleteAlbum(album.id);
            setSheet(null);
            setPlace(null);
          }} />
        </Sheet>
      )}

      {viewer && (
        <PhotoViewer
          ids={viewer.ids}
          start={viewer.index}
          albumId={album?.id}
          onClose={() => setViewer(null)}
          onShare={(p) => void share([p])}
          onDelete={async (p) => {
            if (!window.confirm(`Remove “${p.name}” from Mortimer? The file on your device is not touched.`)) return false;
            await removePhotos([p.id]);
            return true;
          }}
          onSetCover={album ? (p) => { setAlbumCover(album.id, p.id); actions.toast("Album cover updated"); } : undefined}
        />
      )}
    </section>
  );
}

const Tile = memo(function Tile({ photo, favorite, selecting, selected, onOpen, onLongPress }: {
  photo: Photo; favorite: boolean; selecting: boolean; selected: boolean; onOpen: () => void; onLongPress: () => void;
}) {
  const timer = useRef<number | undefined>(undefined);
  const longPressed = useRef(false);
  const start = () => {
    longPressed.current = false;
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => { longPressed.current = true; onLongPress(); navigator.vibrate?.(15); }, 450);
  };
  const cancel = () => window.clearTimeout(timer.current);
  return (
    <button
      className={`photoTile${selected ? " selected" : ""}${selecting ? " selecting" : ""}`}
      aria-label={photo.name}
      aria-pressed={selecting ? selected : undefined}
      onPointerDown={start}
      onPointerUp={cancel}
      onPointerLeave={cancel}
      onPointerCancel={cancel}
      onContextMenu={(e) => { e.preventDefault(); if (!selecting) onLongPress(); }}
      onClick={() => { if (longPressed.current) { longPressed.current = false; return; } onOpen(); }}
    >
      <img src={thumbUrl(photo)} alt="" loading="lazy" decoding="async" draggable={false} />
      {photo.mime === "image/gif" && <em className="photoBadge">GIF</em>}
      {favorite && !selecting && <Heart className="photoFav" size={15} fill="currentColor" />}
      {selecting && <span className="photoCheck">{selected && <Check size={14} strokeWidth={3} />}</span>}
    </button>
  );
});

function AlbumsView({ albums, photos, byId, onOpen, onCreate }: {
  albums: PhotoAlbum[]; photos: Photo[]; byId: Map<string, Photo>; onOpen: (p: Place) => void; onCreate: () => void;
}) {
  const folders = useMemo(() => {
    const map = new Map<string, Photo[]>();
    for (const p of photos) {
      const list = map.get(p.folder);
      if (list) list.push(p);
      else map.set(p.folder, [p]);
    }
    return [...map.entries()]
      .map(([path, list]) => ({ path, list, latest: list.reduce((a, b) => (b.takenAt > a.takenAt ? b : a)) }))
      .sort((a, b) => b.latest.takenAt - a.latest.takenAt);
  }, [photos]);

  return (
    <div className="albumsView">
      <div className="sectionTitle"><h2>My albums</h2></div>
      <div className="photoAlbumGrid">
        <button className="photoAlbumCard create" onClick={onCreate}>
          <span className="photoAlbumCover"><Plus size={30} /></span>
          <b>New album</b>
          <small>Group photos your way</small>
        </button>
        {albums.map((a) => {
          const cover = (a.coverId && byId.get(a.coverId)) || a.photoIds.map((id) => byId.get(id)).find(Boolean);
          const count = a.photoIds.filter((id) => byId.has(id)).length;
          return (
            <button key={a.id} className="photoAlbumCard" onClick={() => onOpen({ kind: "album", id: a.id })}>
              <span className="photoAlbumCover">{cover ? <img src={thumbUrl(cover)} alt="" loading="lazy" /> : <ImageIcon size={30} />}</span>
              <b>{a.name}</b>
              <small>{count} photo{count === 1 ? "" : "s"}</small>
            </button>
          );
        })}
      </div>
      <div className="sectionTitle"><h2>Folders</h2></div>
      <div className="photoAlbumGrid">
        {folders.map((f) => (
          <button key={f.path} className="photoAlbumCard" onClick={() => onOpen({ kind: "folder", path: f.path })}>
            <span className="photoAlbumCover stack"><img src={thumbUrl(f.latest)} alt="" loading="lazy" /></span>
            <b>{f.path.split("/").pop() || "Loose photos"}</b>
            <small>{f.list.length} photo{f.list.length === 1 ? "" : "s"}</small>
          </button>
        ))}
      </div>
    </div>
  );
}
