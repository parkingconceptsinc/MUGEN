export type FolderImportFailures = {
  files: number;
  batches: number;
};

export function recordFolderBatchOutcome(
  failures: FolderImportFailures,
  options: { fileReadFailures: number; incomingCount: number; importSucceeded: boolean },
): FolderImportFailures {
  const fileReadFailures = Number.isFinite(options.fileReadFailures)
    ? Math.max(0, Math.floor(options.fileReadFailures))
    : 0;
  const incomingCount = Number.isFinite(options.incomingCount)
    ? Math.max(0, Math.floor(options.incomingCount))
    : 0;

  return {
    files: failures.files + fileReadFailures,
    batches: failures.batches + (incomingCount > 0 && !options.importSucceeded ? 1 : 0),
  };
}

export function folderImportFailureMessage(failures: FolderImportFailures): string | null {
  if (failures.files === 0 && failures.batches === 0) return null;
  const parts = [
    failures.files ? `${failures.files} file read error${failures.files === 1 ? "" : "s"}` : "",
    failures.batches ? `${failures.batches} batch${failures.batches === 1 ? "" : "es"} could not be fully imported` : "",
  ].filter(Boolean);
  return `Folder import finished with ${parts.join(" and ")}. Check storage and retry affected items.`;
}

export function getImportProgressPercent(done: number, total: number): number | null {
  if (!Number.isFinite(total) || total <= 0 || !Number.isFinite(done)) return null;
  return Math.max(0, Math.min(100, (done / total) * 100));
}
