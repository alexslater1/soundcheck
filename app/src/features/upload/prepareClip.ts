import {
  chooseUpload,
  compressionPlan,
  type Compressed,
  type CompressionPlan,
  type Connection,
} from './compressDecision';
import type { PickedVideo, UploadEntry } from './types';

/** What preparing a clip needs from the outside world; the runner passes the real ones, tests fakes. */
export type PrepareDeps = {
  /** The entry as it is now; throws once it has been removed from the queue. */
  current: () => UploadEntry;
  update: (patch: Partial<UploadEntry>) => void;
  connection: () => Promise<Connection>;
  compress: (
    video: PickedVideo,
    plan: CompressionPlan,
    onProgress: (fraction: number) => void,
  ) => Promise<Compressed | null>;
  discard: (uri: string) => void;
  platform: string;
};

/**
 * The PREPARING step of an upload. The clip is queued at once and this runs in the background, so
 * the user does not wait for compressing (~25 s for a minute of video) before the form: ask what
 * the phone is connected through, compress only off Wi-Fi (and only when it saves something), and
 * swap the file the upload will send. The user writes the post meanwhile, looking at the original
 * (`previewUri`).
 *
 * Returns false when the entry was removed while it ran (any file made for it is deleted).
 */
export async function prepareClip(deps: PrepareDeps): Promise<boolean> {
  const entry = deps.current();
  const original: PickedVideo = {
    uri: entry.fileUri,
    fileName: entry.fileName,
    contentType: entry.contentType,
    bytes: entry.bytes,
    durationMs: entry.durationMs,
    width: entry.width,
    height: entry.height,
  };
  deps.update({ state: 'PREPARING', progress: 0, error: null });
  const connection = await deps.connection();
  const plan = compressionPlan(original, connection);
  const compressed = plan
    ? await deps.compress(original, plan, (fraction) => deps.update({ progress: fraction }))
    : null;
  try {
    deps.current();
  } catch {
    if (compressed) {
      deps.discard(compressed.uri);
    }
    return false;
  }
  const { video, clientMeta, temporary, discard } = chooseUpload(
    original,
    compressed,
    deps.platform,
    connection,
  );
  if (discard) {
    deps.discard(discard);
  }
  deps.update({
    fileUri: video.uri,
    bytes: video.bytes,
    contentType: video.contentType,
    width: video.width,
    height: video.height,
    clientMeta,
    temporary,
    prepared: true,
    state: 'QUEUED',
    progress: 0,
  });
  return true;
}
