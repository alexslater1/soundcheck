import { Video } from 'react-native-compressor';

import { File } from '@/lib/fileSystemNext';

import { compressionPlan, type Compressed } from './compressDecision';
import type { PickedVideo } from './types';

/** Deletes a file the app created (the compressor's output). Never called with the user's original. */
export function discardFile(uri: string): void {
  try {
    const file = new File(uri);
    if (file.exists) {
      file.delete();
    }
  } catch {
    // A leftover file in the cache directory is not worth an error; the system clears the cache.
  }
}

/**
 * Android: re-encode on the phone before uploading: H.264, long edge at
 * most 1920 px, 8 Mbit/s. A minute of 4K camera footage is 300 MB or more; this brings it to about
 * 60 MB, which matters on mobile data, while keeping the detail the server's transcode works from.
 * Returns null when the original is already small enough (see `compressionPlan`) or compression
 * fails: the original is uploaded instead and the server still produces the same renditions.
 */
export async function compressVideo(
  video: PickedVideo,
  onProgress?: (fraction: number) => void,
): Promise<Compressed | null> {
  const plan = compressionPlan(video);
  if (plan === null) {
    return null;
  }
  const started = Date.now();
  try {
    const uri = await Video.compress(
      video.uri,
      { compressionMethod: 'manual', maxSize: plan.maxSize, bitrate: plan.bitrate },
      onProgress,
    );
    const bytes = new File(uri).size;
    if (!bytes) {
      return null;
    }
    return { uri, bytes, elapsedMs: Date.now() - started };
  } catch {
    return null;
  }
}
