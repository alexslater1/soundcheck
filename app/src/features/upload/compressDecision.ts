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
): { video: PickedVideo; clientMeta: ClientMeta } {
  if (compressed === null) {
    return { video: original, clientMeta: { platform } };
  }
  const clientMeta: ClientMeta = {
    platform,
    originalBytes: original.bytes,
    compressedBytes: compressed.bytes,
    compressMs: compressed.elapsedMs,
  };
  if (compressed.bytes >= original.bytes) {
    return { video: original, clientMeta };
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
  };
}
