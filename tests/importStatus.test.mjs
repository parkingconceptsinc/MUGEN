import { test } from 'node:test';
import assert from 'node:assert/strict';
import { folderImportFailureMessage, getImportProgressPercent, recordFolderBatchOutcome } from '../src/importStatus.ts';

test('a partially failed 100-file folder batch is reported as one failed batch, not 100 failed files', () => {
  const result = recordFolderBatchOutcome(
    { files: 0, batches: 0 },
    { fileReadFailures: 0, incomingCount: 100, importSucceeded: false },
  );

  assert.deepEqual(result, { files: 0, batches: 1 });
  assert.equal(folderImportFailureMessage(result), 'Folder import finished with 1 batch could not be fully imported. Check storage and retry affected items.');
});

test('folder import counts actual file-read failures separately from an import batch warning', () => {
  const first = recordFolderBatchOutcome(
    { files: 0, batches: 0 },
    { fileReadFailures: 2, incomingCount: 98, importSucceeded: false },
  );
  const result = recordFolderBatchOutcome(
    first,
    { fileReadFailures: 0, incomingCount: 0, importSucceeded: true },
  );

  assert.deepEqual(result, { files: 2, batches: 1 });
  assert.equal(folderImportFailureMessage(result), 'Folder import finished with 2 file read errors and 1 batch could not be fully imported. Check storage and retry affected items.');
  assert.equal(folderImportFailureMessage({ files: 0, batches: 0 }), null);
});

test('unknown totals stay indeterminate while known totals produce a bounded percentage', () => {
  assert.equal(getImportProgressPercent(18, 0), null);
  assert.equal(getImportProgressPercent(18, Number.NaN), null);
  assert.equal(getImportProgressPercent(0, 100), 0);
  assert.equal(getImportProgressPercent(25, 100), 25);
  assert.equal(getImportProgressPercent(120, 100), 100);
  assert.equal(getImportProgressPercent(-1, 100), 0);
});
