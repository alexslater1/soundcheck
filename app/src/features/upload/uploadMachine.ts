import type {
  CreateUploadRequest,
  PartRequest,
  Playback,
  UploadTicket,
} from '@/api/generated/model';
import { ApiError } from '@/api/http';
import { nextPollDelay } from '@/features/player/mediaPolling';
import { z } from 'zod';

import { withRetries } from './retry';
import type { UploadEntry, UploadTransport } from './types';
import { isPhoto } from './validate';

/**
 * Parts sent at once, per upload: two, not more, because each part in flight is a temporary 8 MiB
 * copy on disk and a native request, and two streams already fill a phone's upload link. Clips are
 * sent one at a time (`oneAtATime`), so this is also the limit for the whole app.
 */
export const PARALLEL_PARTS = 2;
export const PART_RETRIES = 3;
const RETRY_BASE_MS = 500;

/** Everything the machine touches, passed in so tests can replace each piece. */
export type UploadDeps = {
  api: {
    /** `POST /media/uploads` with the entry's `Idempotency-Key`. */
    createUpload: (body: CreateUploadRequest, idempotencyKey: string) => Promise<UploadTicket>;
    completeUpload: (mediaId: string, parts: PartRequest[] | null) => Promise<unknown>;
    getMedia: (mediaId: string) => Promise<Playback>;
  };
  transport: UploadTransport;
  /** The entry as it is now (parts uploaded so far included). */
  current: () => UploadEntry;
  /** Merges a change into the entry (the store persists it). */
  update: (patch: Partial<UploadEntry>) => void;
  sleep: (ms: number) => Promise<void>;
  random: () => number;
  /**
   * Called once the server has accepted the upload, before the wait for processing: the moment a
   * post can be created for the clip (the user does not wait for the transcode).
   */
  onAccepted?: () => Promise<void>;
  /**
   * Runs the byte-sending step (ticket, bytes, complete). The runner passes a gate shared by every
   * upload, so a post's clips are sent one after another rather than all at once; the
   * PREPARING step and the wait for the worker are outside it. Without one, the step just runs.
   */
  oneAtATime?: <T>(step: () => Promise<T>) => Promise<T>;
  /**
   * The file's hash for the duplicate guard: `md5:…` on the phone, `sha256:…` in the browser,
   * null when it cannot be made. Asked for just before the ticket; without it the ticket has no hash.
   */
  contentHash?: () => Promise<string | null>;
  /**
   * The server refused the ticket because this file is already in one of the user's live posts
   * (409 DUPLICATE_UPLOAD): the entry is not FAILED (there is nothing to retry); the runner takes it off
   * the list and says so, with a link to that post.
   */
  onDuplicate?: (postId: string) => Promise<void>;
};

/** The extension field of a 409 DUPLICATE_UPLOAD problem. */
const duplicateProblem = z.object({ postId: z.string() });

/** The post a refused ticket names, when the refusal was 409 DUPLICATE_UPLOAD. */
export function duplicateOf(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 409 || error.code !== 'DUPLICATE_UPLOAD') {
    return null;
  }
  const parsed = duplicateProblem.safeParse(error.problem);
  return parsed.success ? parsed.data.postId : null;
}

/**
 * Takes one queue entry from wherever it is to a settled state:
 * ticket → bytes to storage (one PUT, or parts four at a time) → complete → poll until the worker is
 * done. Never throws: a failure ends in state FAILED with a message, and running the entry again
 * continues from the parts already uploaded.
 */
export async function runUpload(deps: UploadDeps): Promise<void> {
  const entry = deps.current();
  if (entry.state === 'READY' || entry.state === 'REJECTED') {
    return;
  }
  try {
    const mediaId =
      entry.mediaId !== null &&
      (entry.state === 'PROCESSING' || (await accepted(entry.mediaId, deps)))
        ? entry.mediaId
        : await (deps.oneAtATime ?? ((step) => step()))(() => upload(deps));
    deps.update({ state: 'PROCESSING', progress: 1, error: null });
    await deps.onAccepted?.();
    await pollUntilSettled(mediaId, deps);
  } catch (error) {
    const postId = duplicateOf(error);
    if (postId !== null && deps.onDuplicate) {
      await deps.onDuplicate(postId);
      return;
    }
    deps.update({ state: 'FAILED', error: describe(error) });
  }
}

/**
 * Has the server already accepted this upload? True after a `complete` that succeeded on the server
 * while its answer never reached the app. Asking for the ticket again would then be refused
 * (409 UPLOAD_ALREADY_COMPLETED), so a retry must not start over. When the server cannot be asked,
 * assume it has not: the normal path will say what is wrong.
 */
async function accepted(mediaId: string, deps: UploadDeps): Promise<boolean> {
  try {
    return (await deps.api.getMedia(mediaId)).status !== 'AWAITING_UPLOAD';
  } catch {
    return false;
  }
}

/** Ticket, bytes, complete. Returns the media id. */
async function upload(deps: UploadDeps): Promise<string> {
  const entry = deps.current();
  deps.update({ state: 'REQUESTING', error: null });
  // A photo is an IMAGE upload: the server checks and cleans it on "complete" and it is READY at
  // once, so the poll below ends at its first answer.
  const photo = isPhoto(entry);
  const contentHash = deps.contentHash ? await deps.contentHash().catch(() => null) : null;
  const ticket = await deps.api.createUpload(
    {
      kind: photo ? 'IMAGE' : 'VIDEO',
      contentType: entry.contentType,
      bytes: entry.bytes,
      durationMs: photo ? null : entry.durationMs,
      width: entry.width,
      height: entry.height,
      clientMeta: entry.clientMeta,
      contentHash,
    },
    entry.idempotencyKey,
  );
  if (entry.mediaId !== null && entry.mediaId !== ticket.mediaId) {
    // The server opened a new upload (the idempotency key had expired after a day): the ETags kept
    // from the old one belong to a multipart upload that no longer exists.
    deps.update({ parts: {}, progress: 0 });
  }
  deps.update({ mediaId: ticket.mediaId, strategy: ticket.strategy, state: 'UPLOADING' });

  const source = { uri: entry.fileUri, bytes: entry.bytes };
  const retry = {
    retries: PART_RETRIES,
    baseMs: RETRY_BASE_MS,
    sleep: deps.sleep,
    random: deps.random,
  };
  if (ticket.strategy === 'SINGLE') {
    const url = required(ticket.putUrl, 'the ticket has no upload URL');
    await withRetries(() => deps.transport.putWhole(url, ticket.headers ?? {}, source), retry);
    deps.update({ progress: 1, state: 'COMPLETING' });
    await deps.api.completeUpload(ticket.mediaId, null);
    return ticket.mediaId;
  }

  const partSize = required(ticket.partSize, 'the ticket has no part size');
  const parts = required(ticket.parts, 'the ticket has no parts');
  // Parts uploaded by an earlier run of this entry are not sent again.
  const pending = parts.filter((part) => deps.current().parts[part.partNumber] === undefined);
  const sizeOf = (partNumber: number) =>
    Math.min(partSize, entry.bytes - (partNumber - 1) * partSize);
  let failure: unknown = null;

  const worker = async () => {
    for (;;) {
      const part = failure === null ? pending.shift() : undefined;
      if (part === undefined) {
        return;
      }
      try {
        const etag = await withRetries(
          () =>
            deps.transport.putPart(
              part.url,
              source,
              (part.partNumber - 1) * partSize,
              sizeOf(part.partNumber),
            ),
          retry,
        );
        const done = { ...deps.current().parts, [part.partNumber]: etag };
        const uploaded = Object.keys(done).reduce((sum, n) => sum + sizeOf(Number(n)), 0);
        deps.update({ parts: done, progress: uploaded / entry.bytes });
      } catch (error) {
        // One part is out of retries: the others finish what they are sending and take no more.
        failure = error;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(PARALLEL_PARTS, pending.length) }, worker));
  if (failure !== null) {
    throw failure;
  }

  deps.update({ state: 'COMPLETING' });
  const etags = deps.current().parts;
  await deps.api.completeUpload(
    ticket.mediaId,
    parts.map((part) => ({
      partNumber: part.partNumber,
      etag: required(etags[part.partNumber], `part ${part.partNumber} was not uploaded`),
    })),
  );
  return ticket.mediaId;
}

/** Polls `GET /media/{id}` with the backoff of `nextPollDelay` until the worker has finished. */
async function pollUntilSettled(mediaId: string, deps: UploadDeps): Promise<void> {
  for (let polls = 1; ; polls++) {
    let status: Playback['status'] | undefined;
    try {
      status = (await deps.api.getMedia(mediaId)).status;
    } catch {
      // A failed poll is not a failed upload: ask again at the next interval.
    }
    if (status === 'READY' || status === 'REJECTED') {
      deps.update({ state: status });
      return;
    }
    if (status === 'FAILED' || status === 'DELETED') {
      deps.update({ state: 'FAILED', error: 'Processing failed on the server.' });
      return;
    }
    const delay = nextPollDelay(status, polls);
    if (delay === false) {
      // Still processing after five minutes: stop asking; opening the uploads screen asks again.
      return;
    }
    await deps.sleep(delay);
  }
}

function required<T>(value: T | null | undefined, message: string): T {
  if (value === null || value === undefined) {
    throw new Error(message);
  }
  return value;
}

function describe(error: unknown): string {
  return error instanceof Error && error.message ? error.message : 'Upload failed.';
}
