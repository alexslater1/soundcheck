import type { PickedVideo } from './types';

/** Clips are at most 60 s (the server allows one more second of tolerance) and 500 MB. */
export const MAX_DURATION_MS = 61_000;
export const MAX_BYTES = 500 * 1024 * 1024;

/**
 * Checks a picked file before any bytes move. Returns what to tell the user, or null when it may be
 * uploaded. The server checks again (the client can be wrong or bypassed); this only saves the user
 * an upload that is certain to be refused.
 */
export function problemWith(video: PickedVideo): string | null {
  if (!video.contentType.startsWith('video/')) {
    return 'Choose a video file.';
  }
  if (video.bytes <= 0) {
    return 'This file is empty.';
  }
  if (video.bytes > MAX_BYTES) {
    return 'This video is larger than 500 MB.';
  }
  // The length is unknown when the device or browser cannot read the file's metadata.
  if (video.durationMs !== null && video.durationMs > MAX_DURATION_MS) {
    return 'Clips can be up to 60 seconds long.';
  }
  return null;
}
