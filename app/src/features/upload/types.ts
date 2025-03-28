/** A video the user picked or recorded, before anything is uploaded. */
export type PickedVideo = {
  /** `file://…` on Android, a `blob:` URL in the browser. */
  uri: string;
  fileName: string | null;
  contentType: string;
  bytes: number;
  durationMs: number | null;
  width: number | null;
  height: number | null;
};

/** What the client tells the server about how the file was prepared. */
export type ClientMeta = {
  platform: string;
  originalBytes?: number;
  compressedBytes?: number;
  compressMs?: number;
};

/**
 * Where an upload is. QUEUED → REQUESTING (ticket) → UPLOADING → COMPLETING → PROCESSING (the worker
 * has it) → READY | REJECTED | FAILED. FAILED before PROCESSING can be retried from where it stopped.
 */
export type UploadState =
  | 'QUEUED'
  | 'REQUESTING'
  | 'UPLOADING'
  | 'COMPLETING'
  | 'PROCESSING'
  | 'READY'
  | 'REJECTED'
  | 'FAILED';

/**
 * One entry of the upload queue, persisted after every change so a later version can resume after an
 * app restart. The presigned URLs are deliberately not stored: asking again with the same
 * idempotency key returns the same upload with freshly signed URLs.
 */
export type UploadEntry = {
  localId: string;
  idempotencyKey: string;
  createdAt: number;
  fileUri: string;
  fileName: string | null;
  contentType: string;
  bytes: number;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  clientMeta: ClientMeta;
  /** True when `fileUri` is a file the app made itself (the compressor's output) and must delete. */
  temporary: boolean;
  state: UploadState;
  /** Share of the bytes that storage has acknowledged, 0 to 1. */
  progress: number;
  mediaId: string | null;
  strategy: 'SINGLE' | 'MULTIPART' | null;
  /** Uploaded parts: part number → ETag. Completing a multipart upload needs every one of them. */
  parts: Record<number, string>;
  error: string | null;
};

/** The file to send: where it is and how long it is. */
export type UploadSource = { uri: string; bytes: number };

/**
 * How bytes leave the device. Two implementations: `transport.ts` (Android, reads byte ranges from the
 * file) and `transport.web.ts` (browser, `Blob.slice`). The state machine knows only this interface.
 */
export interface UploadTransport {
  /** PUTs the whole file with the signed headers. */
  putWhole(url: string, headers: Record<string, string>, source: UploadSource): Promise<void>;
  /** PUTs `length` bytes starting at `offset`; resolves with the part's ETag. */
  putPart(url: string, source: UploadSource, offset: number, length: number): Promise<string>;
}
