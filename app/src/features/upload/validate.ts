import { Platform } from 'react-native';

import type { PickedVideo } from './types';

/**
 * Clips of up to 3 minutes, a whole song (the server allows one more second, for a camera that
 * stops a little late), and uploads of up to 1 GB. A bigger file is compressed on the phone first,
 * even on Wi-Fi (`compressionPlan`).
 */
export const MAX_DURATION_MS = 181_000;
export const MAX_BYTES = 1024 * 1024 * 1024;
/** The length limit in words, for hints and refusals. */
export const CLIP_LENGTH = '3 minutes';

/**
 * Checks a picked file before any bytes move. Returns what to tell the user, or null when it may be
 * uploaded. The server checks again (the client can be wrong or bypassed); this only saves the user
 * an upload that is certain to be refused. On a phone a file over 1 GB is fine here: it is compressed
 * before it is sent. The browser cannot compress, so there it is refused.
 */
export function problemWith(video: PickedVideo, platform: string = Platform.OS): string | null {
  if (!video.contentType.startsWith('video/')) {
    return 'Choose a video file.';
  }
  if (video.bytes <= 0) {
    return 'This file is empty.';
  }
  if (video.bytes > MAX_BYTES && platform === 'web') {
    return 'This video is larger than 1 GB.';
  }
  // The length is unknown when the device or browser cannot read the file's metadata.
  if (video.durationMs !== null && video.durationMs > MAX_DURATION_MS) {
    return `Clips can be up to ${CLIP_LENGTH} long.`;
  }
  return null;
}
