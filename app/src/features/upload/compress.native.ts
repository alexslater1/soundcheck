import { Video } from 'react-native-compressor';

import { File } from '@/lib/fileSystemNext';

import type { Compressed } from './compressDecision';
import type { PickedVideo } from './types';

/**
 * Android: re-encode on the phone before uploading: H.264, long edge at most 1280 px, bitrate
 * chosen by the library. A minute of 1080p camera footage is 100 MB or more; this typically removes
 * most of it, which matters on mobile data. Returns null when compression fails: the original is
 * uploaded instead and the server transcode still produces the same renditions.
 */
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

export async function compressVideo(
  video: PickedVideo,
  onProgress?: (fraction: number) => void,
): Promise<Compressed | null> {
  const started = Date.now();
  try {
    const uri = await Video.compress(
      video.uri,
      { compressionMethod: 'auto', maxSize: 1280 },
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
