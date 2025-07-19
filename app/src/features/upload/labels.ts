import type { UploadEntry } from './types';

/** "18.4 MB" */
export function megabytes(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** What the user reads for each state of an upload. */
export function stateLabel(entry: Pick<UploadEntry, 'state' | 'progress'>): string {
  switch (entry.state) {
    case 'PREPARING':
      return entry.progress > 0
        ? `Compressing for mobile data ${Math.round(entry.progress * 100)}%`
        : 'Getting ready…';
    case 'QUEUED':
      // Clips are sent one at a time: this one waits for the clip before it.
      return 'Waiting for its turn…';
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

type PostFields = Pick<UploadEntry, 'state' | 'draft' | 'postId' | 'postError'> &
  Partial<Pick<UploadEntry, 'attached'>>;

/** In its post with its video: nothing left to do for it. */
function inPost(entry: PostFields): boolean {
  return entry.postId !== null && entry.attached !== false;
}

/** True while the post written for an upload can still come into being without the user doing anything. */
export function postOnItsWay(entry: PostFields): boolean {
  return (
    entry.draft !== null &&
    !entry.postId &&
    !entry.postError &&
    entry.state !== 'REJECTED' &&
    entry.state !== 'FAILED'
  );
}

/**
 * True when a request for the post failed: "Post again" sends it again. Creating the post can be
 * retried at any time; putting a clip into its slot only once the server has the clip.
 */
export function canPostAgain(entry: PostFields): boolean {
  return (
    entry.draft !== null &&
    entry.postError !== null &&
    entry.state !== 'REJECTED' &&
    (!entry.postId || entry.state === 'PROCESSING' || entry.state === 'READY')
  );
}

/** What the row says about the post that goes with an upload; null when nothing was written for it. */
export function postNote(entry: PostFields): { text: string; problem: boolean } | null {
  if (inPost(entry)) {
    return { text: 'Posted', problem: false };
  }
  if (!entry.draft && !entry.postId) {
    return null;
  }
  if (entry.state === 'REJECTED') {
    return entry.postId
      ? { text: 'Left out of the post: this clip could not be used.', problem: true }
      : { text: 'Not posted: this clip could not be used.', problem: true };
  }
  if (entry.postError) {
    return { text: `Not posted: ${entry.postError}`, problem: true };
  }
  if (entry.state === 'FAILED') {
    return { text: 'Will be posted once the upload succeeds. Retry it.', problem: false };
  }
  if (entry.postId) {
    return { text: 'In the post: it shows there once it has uploaded', problem: false };
  }
  return { text: 'Posting…', problem: false };
}
