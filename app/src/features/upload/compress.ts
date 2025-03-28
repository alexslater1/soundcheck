import type { Compressed } from './compressDecision';
import type { PickedVideo } from './types';

/**
 * Web: no client compression in v1; the server's transcode does the work. The
 * Android implementation is `compress.native.ts`.
 */
/** Web has no files of its own to delete. */
export function discardFile(_uri: string): void {}

export async function compressVideo(
  _video: PickedVideo,
  _onProgress?: (fraction: number) => void,
): Promise<Compressed | null> {
  return null;
}
