import type { ClientMeta, PickedVideo } from './types';

export type Compressed = { uri: string; bytes: number; elapsedMs: number };

/** The connection the upload will go over, as far as the phone can tell (`connection.ts`). */
export type Connection = 'wifi' | 'cellular' | 'unknown';

/**
 * Client compression settings (fixed, not an "auto bitrate"). The server makes its own renditions
 * (top rung 720p at 3 Mbit/s), so the phone's job is only to stop a 4K or 50 Mbit/s original from
 * crossing mobile data, without throwing away detail the server would have used. 1080p at 8 Mbit/s
 * keeps that detail (the server still downscales from a sharp source); "auto" mode picked a
 * WhatsApp-like ~1–2 Mbit/s at 720p, and the second encode on the server made it visibly blocky.
 */
export const CLIENT_MAX_LONG_EDGE = 1920;
export const CLIENT_TARGET_BITRATE = 8_000_000;
/** A source this close to the target is not worth a lossy re-encode. */
const NOT_WORTH_IT = 1.25;

export type CompressionPlan = { maxSize: number; bitrate: number };

/** Bits per second of a picked video, or null when its duration is unknown. */
export function sourceBitrate(video: PickedVideo): number | null {
  if (!video.durationMs || video.durationMs <= 0) {
    return null;
  }
  return (video.bytes * 8) / (video.durationMs / 1000);
}

/**
 * Whether and how to compress before uploading. Null means upload the original:
 *
 * - On Wi-Fi, always. Measured on the Pixel (2025-06-02): 140.6 MB took 24.8 s to compress to 57.4 MB,
 *   so compressing pays off only when the upload is slower than about 27 Mbit/s — usual on 4G, rare on
 *   Wi-Fi — and the original is the better source for the server's transcode.
 * - When the file is already no bigger than 1080p at about the target bitrate: re-encoding would cost
 *   quality and battery and save little.
 *
 * On mobile data, or when the connection is unknown (the safe side for the user's data plan), anything
 * else is compressed. Unknown sizes or durations are compressed too.
 */
export function compressionPlan(
  video: PickedVideo,
  connection: Connection,
): CompressionPlan | null {
  if (connection === 'wifi') {
    return null;
  }
  const bitrate = sourceBitrate(video);
  const longEdge =
    video.width && video.height ? Math.max(video.width, video.height) : Number.POSITIVE_INFINITY;
  if (
    bitrate !== null &&
    bitrate <= CLIENT_TARGET_BITRATE * NOT_WORTH_IT &&
    longEdge <= CLIENT_MAX_LONG_EDGE
  ) {
    return null;
  }
  return { maxSize: CLIENT_MAX_LONG_EDGE, bitrate: CLIENT_TARGET_BITRATE };
}

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
  connection: Connection,
): {
  video: PickedVideo;
  clientMeta: ClientMeta;
  /** The upload is the compressor's output: delete it once the bytes are with the server. */
  temporary: boolean;
  /** A compressed file that will not be used: delete it now. */
  discard: string | null;
} {
  if (compressed === null) {
    return {
      video: original,
      clientMeta: { platform, connection },
      temporary: false,
      discard: null,
    };
  }
  const clientMeta: ClientMeta = {
    platform,
    connection,
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
