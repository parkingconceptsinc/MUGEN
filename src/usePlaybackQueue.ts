import { useCallback, useEffect, useState, type Dispatch, type SetStateAction } from "react";
import { readPref, writePref } from "./prefs";
import { readQueueSnapshot } from "./playbackQueue";

export function readPlaybackQueue() {
  return readQueueSnapshot(readPref("playbackQueue", null), readPref("queue", []), readPref("qIndex", 0));
}

export function usePlaybackQueue() {
  // One localStorage write stores order and selected position together. Legacy
  // keys remain intact until a complete snapshot has been saved successfully.
  const [snapshot, setSnapshot] = useState(readPlaybackQueue);
  useEffect(() => { writePref("playbackQueue", snapshot); }, [snapshot]);
  const setQueue: Dispatch<SetStateAction<string[]>> = useCallback(update => {
    setSnapshot(previous => ({ ...previous, ids: typeof update === "function" ? update(previous.ids) : update }));
  }, [setSnapshot]);
  const setIndex: Dispatch<SetStateAction<number>> = useCallback(update => {
    setSnapshot(previous => ({ ...previous, index: typeof update === "function" ? update(previous.index) : update }));
  }, [setSnapshot]);
  return [snapshot.ids, setQueue, snapshot.index, setIndex] as const;
}
