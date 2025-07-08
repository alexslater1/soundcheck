import { canPostAgain } from './labels';
import type { UploadEntry } from './types';
import { useUploadQueue } from './uploadQueue';

/**
 * Something about an upload that the user has to act on: the bytes did not get there
 * (FAILED: Try again continues from the parts already sent), the post could not be created although
 * the clip is safe (Try again sends it again), or the server could not use the file (REJECTED: only
 * removing it helps). The Create tab's badge counts these, and its banner names them.
 */
export type UploadProblem = {
  localId: string;
  kind: 'upload' | 'post' | 'rejected';
  /** The clip's name as the user knows it. */
  name: string;
  /** In plain words, completing "Upload failed — …". */
  reason: string;
  /** The technical message, shown behind "Details". */
  details: string;
};

/**
 * What a technical error means for the user. The messages come from the phone (the native upload,
 * the file system, the app's own checks) or from the server, and only the common causes are
 * recognised; anything else is "something went wrong", with the real message under Details.
 */
export function plainReason(error: string | null): string {
  const message = error ?? '';
  if (/OutOfMemory|out of memory|Failed to allocate/i.test(message)) {
    return 'the phone ran out of memory';
  }
  if (/Interrupted before it finished/i.test(message)) {
    return 'the app was closed before it finished';
  }
  if (/shorter than expected|does not exist|no such file|ENOENT/i.test(message)) {
    return 'the video is no longer on the phone';
  }
  if (/Storage answered 403/i.test(message)) {
    return 'the upload link expired';
  }
  if (/Storage answered 5\d\d/i.test(message)) {
    return 'storage was not available';
  }
  if (/larger than 1 GB and could not be compressed/i.test(message)) {
    return 'the video is over 1 GB and the phone could not compress it';
  }
  if (/Too many requests/i.test(message)) {
    return 'too many uploads in the last hour; wait a little';
  }
  // The server's own refusals are already plain sentences ("Clips can be at most 3 minutes").
  if (/^(Clips|Videos) (can|must) /.test(message)) {
    return message.charAt(0).toLowerCase() + message.slice(1).replace(/\.$/, '');
  }
  if (/Processing failed/i.test(message)) {
    return 'the server could not process the clip';
  }
  if (
    /Could not reach the server|timeout|timed out|Unable to resolve host|Failed to connect|connection|Network request failed|unexpected end of stream|Software caused/i.test(
      message,
    )
  ) {
    return 'the connection dropped';
  }
  return 'something went wrong';
}

/** The entry's problem, or null when there is nothing the user needs to do. */
export function problemOf(entry: UploadEntry): UploadProblem | null {
  const name = entry.fileName ?? 'Recorded clip';
  if (entry.state === 'FAILED') {
    const details = entry.error ?? 'No message';
    return { localId: entry.localId, kind: 'upload', name, reason: plainReason(details), details };
  }
  if (entry.state === 'REJECTED') {
    return {
      localId: entry.localId,
      kind: 'rejected',
      name,
      reason: 'the server could not use this file as a video',
      details: 'The server rejected the file (not a readable video, or longer than the limit).',
    };
  }
  if (canPostAgain(entry)) {
    const details = entry.postError ?? 'No message';
    return { localId: entry.localId, kind: 'post', name, reason: plainReason(details), details };
  }
  return null;
}

/** Every problem among one account's uploads, newest first (the queue's order). */
export function problemsOf(entries: (UploadEntry & { ownerId: string })[], ownerId: string) {
  return entries
    .filter((entry) => entry.ownerId === ownerId)
    .map(problemOf)
    .filter((problem): problem is UploadProblem => problem !== null);
}

/**
 * The banner's sentence: "Upload failed — the phone ran out of memory. Try again", or for several,
 * "2 uploads failed — the connection dropped." (one shared cause) or "2 uploads need attention."
 */
export function bannerText(problems: UploadProblem[]): string {
  const retryable = problems.some((problem) => problem.kind !== 'rejected');
  if (problems.length === 1) {
    const [only] = problems as [UploadProblem];
    const what =
      only.kind === 'post'
        ? 'Posting failed'
        : only.kind === 'rejected'
          ? 'This clip could not be used'
          : 'Upload failed';
    return `${what} — ${only.reason}.${retryable ? ' Try again' : ' Remove it below.'}`;
  }
  const reasons = new Set(problems.map((problem) => problem.reason));
  const [shared] = [...reasons];
  return reasons.size === 1 && shared
    ? `${problems.length} uploads failed — ${shared}.${retryable ? ' Try again' : ''}`
    : `${problems.length} uploads need attention.`;
}

/** How many of an account's uploads need the user (the Create tab's badge); 0 when signed out. */
export function useUploadProblemCount(ownerId: string | null): number {
  return useUploadQueue((state) => (ownerId ? problemsOf(state.entries, ownerId).length : 0));
}
