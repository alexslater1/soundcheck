import type { UploadEntry } from './types';

/** "18.4 MB" */
export function megabytes(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** What the user reads for each state of an upload. */
export function stateLabel(entry: Pick<UploadEntry, 'state' | 'progress'>): string {
  switch (entry.state) {
    case 'QUEUED':
      return 'Waiting…';
    case 'REQUESTING':
      return 'Starting…';
    case 'UPLOADING':
      return `Uploading ${Math.round(entry.progress * 100)}%`;
    case 'COMPLETING':
      return 'Finishing…';
    case 'PROCESSING':
      return 'Processing…';
    case 'READY':
      return 'Ready';
    case 'REJECTED':
      return 'Not a usable video';
    case 'FAILED':
      return 'Failed';
  }
}

/** "Compressed 57.2 MB → 17.2 MB (70% smaller)", or null when the phone did not compress. */
export function compressionNote(entry: Pick<UploadEntry, 'clientMeta'>): string | null {
  const { originalBytes, compressedBytes } = entry.clientMeta;
  if (!originalBytes || !compressedBytes) {
    return null;
  }
  if (compressedBytes >= originalBytes) {
    return `Compression did not help (${megabytes(originalBytes)}); the original was uploaded`;
  }
  const saved = Math.round((1 - compressedBytes / originalBytes) * 100);
  return `Compressed ${megabytes(originalBytes)} → ${megabytes(compressedBytes)} (${saved}% smaller)`;
}
