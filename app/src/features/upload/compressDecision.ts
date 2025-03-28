import type { ClientMeta, PickedVideo } from './types';

export type Compressed = { uri: string; bytes: number; elapsedMs: number };

/**
 * Which file to upload after compressing, and what to report about it: the compressed one, unless
 * compression made the file bigger (already-efficient sources do that), in which case the original
 * goes up. The sizes and the time are recorded either way: they are the data behind the "how much
 * does client compression save" measurement.
 */
export function chooseUpload(
  original: PickedVideo,
  compressed: Compressed | null,
  platform: string,
): {
  video: PickedVideo;
  clientMeta: ClientMeta;
  /** The upload is the compressor's output: delete it once the bytes are with the server. */
  temporary: boolean;
  /** A compressed file that will not be used: delete it now. */
  discard: string | null;
} {
  if (compressed === null) {
    return { video: original, clientMeta: { platform }, temporary: false, discard: null };
  }
  const clientMeta: ClientMeta = {
    platform,
    originalBytes: original.bytes,
    compressedBytes: compressed.bytes,
    compressMs: compressed.elapsedMs,
  };
  if (compressed.bytes >= original.bytes) {
    return { video: original, clientMeta, temporary: false, discard: compressed.uri };
  }
  return {
    // The compressor writes MP4 (H.264) whatever the source was; its frame size is no longer the original's.
    video: {
      ...original,
      uri: compressed.uri,
      bytes: compressed.bytes,
      contentType: 'video/mp4',
      width: null,
      height: null,
    },
    clientMeta,
    temporary: true,
    discard: null,
  };
}
