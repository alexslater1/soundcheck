import type {
  CreateUploadRequest,
  PartRequest,
  Playback,
  UploadTicket,
} from '@/api/generated/model';
import { nextPollDelay } from '@/features/player/mediaPolling';

import { withRetries } from './retry';
import type { UploadEntry, UploadTransport } from './types';

export const PARALLEL_PARTS = 4;
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
};

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
    const alreadyCompleted = entry.state === 'PROCESSING' && entry.mediaId !== null;
    const mediaId = alreadyCompleted ? entry.mediaId! : await upload(deps);
    deps.update({ state: 'PROCESSING', progress: 1, error: null });
    await pollUntilSettled(mediaId, deps);
  } catch (error) {
    deps.update({ state: 'FAILED', error: describe(error) });
  }
}

/** Ticket, bytes, complete. Returns the media id. */
async function upload(deps: UploadDeps): Promise<string> {
  const entry = deps.current();
  deps.update({ state: 'REQUESTING', error: null });
  const ticket = await deps.api.createUpload(
    {
      kind: 'VIDEO',
      contentType: entry.contentType,
      bytes: entry.bytes,
      durationMs: entry.durationMs,
      width: entry.width,
      height: entry.height,
      clientMeta: entry.clientMeta,
    },
    entry.idempotencyKey,
  );
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
