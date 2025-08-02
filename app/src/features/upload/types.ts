/**
 * A video the user picked or recorded, or a photo they picked, before anything is
 * uploaded. The content type tells them apart (`isPhoto`); a photo has no duration.
 */
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
  /** Wi-Fi uploads the original; mobile data or unknown compresses. */
  connection?: 'wifi' | 'cellular' | 'unknown';
  originalBytes?: number;
  compressedBytes?: number;
  compressMs?: number;
};

/**
 * Where an upload is. PREPARING (the phone compresses it, off Wi-Fi only) → QUEUED →
 * REQUESTING (ticket) → UPLOADING → COMPLETING → PROCESSING (the worker has it) → READY | REJECTED |
 * FAILED. FAILED before PROCESSING can be retried from where it stopped.
 */
export type UploadState =
  | 'PREPARING'
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
  /**
   * False until the phone has decided whether to compress and done it: the PREPARING step
   * runs inside the queue, so picking a clip returns at once and the user writes the post meanwhile.
   */
  prepared: boolean;
  /** The file as picked: the compose form previews it, whatever is uploaded. */
  previewUri: string;
  state: UploadState;
  /** Share of the bytes that storage has acknowledged, 0 to 1. */
  progress: number;
  mediaId: string | null;
  strategy: 'SINGLE' | 'MULTIPART' | null;
  /** Uploaded parts: part number → ETag. Completing a multipart upload needs every one of them. */
  parts: Record<number, string>;
  error: string | null;
  /** What the user wrote on the compose form; null until they press Post. */
  draft: PostDraft | null;
  /**
   * A clip posted before a post could hold several clips used this as its post's key; it now names
   * the clip's group then.
   */
  postIdempotencyKey: string;
  /** Set once the post exists on the server. */
  postId: string | null;
  /** The clip's slot in the post, set with `postId`. */
  clipId: string | null;
  /** Whether the slot has this clip's video (sent with the post, or filled once the upload completed). */
  attached: boolean;
  /** The act on stage in this clip (the act bar); null: no act. */
  artistId: string | null;
  /** A frame of the clip, uploaded as an image, shown blurred while the clip is on its way. */
  previewMediaId: string | null;
  previewState: 'NONE' | 'MAKING' | 'READY' | 'FAILED';
  /** Whether the slot has the preview (no need once the clip itself is attached). */
  previewSent: boolean;
  postError: string | null;
};

/**
 * One post being made from several uploads. Every entry of the post carries the same draft:
 * `groupKey` names the post (it is the `Idempotency-Key` of `POST /posts`), `order` is the entry's place
 * among the clips announced (running order). `request` is the exact body sent the first time, kept so a
 * retry sends the same request (a reused key with another body is refused).
 */
export type PostDraft = {
  groupKey: string;
  order: number;
  caption: string;
  gigId: string | null;
  /** The UK day the clips are from (a festival day); null: the gig's day. */
  day: string | null;
  artistIds: string[];
  taggedUserIds: string[];
  /** Adding clips to an existing post (`POST /posts/{id}/clips`) instead of creating one. */
  joinPostId: string | null;
  request: PostRequest | null;
};

/** What was sent for a draft: a whole post, or clips for an existing one. */
export type PostRequest = {
  clips: { mediaId: string | null; artistId: string | null; previewMediaId: string | null }[];
  caption?: string | null;
  gigId?: string | null;
  day?: string | null;
  artistIds?: string[];
  taggedUserIds?: string[];
};

/** The file to send: where it is and how long it is. */
export type UploadSource = { uri: string; bytes: number };

/**
 * How bytes leave the device. Two implementations: `transport.ts` (Android, streams the file natively;
 * a part is copied to a temporary file first) and `transport.web.ts` (browser, `Blob.slice`). The state machine knows only this interface.
 */
export interface UploadTransport {
  /** PUTs the whole file with the signed headers. */
  putWhole(url: string, headers: Record<string, string>, source: UploadSource): Promise<void>;
  /** PUTs `length` bytes starting at `offset`; resolves with the part's ETag. */
  putPart(url: string, source: UploadSource, offset: number, length: number): Promise<string>;
  /** Deletes temporary copies an earlier run left behind; called only while nothing is uploading. */
  discardLeftovers(): void;
}
