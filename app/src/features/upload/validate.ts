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
 * A post's slot may hold a photo. The server keeps JPEG, PNG and WebP of up to 10 MB
 * (`UploadService.IMAGE_TYPES`, `soundcheck.media.max-image-size`); a phone's HEIC is refused here
 * rather than by the server after the upload.
 */
export const PHOTO_TYPES: readonly string[] = ['image/jpeg', 'image/png', 'image/webp'];
export const MAX_PHOTO_BYTES = 10 * 1024 * 1024;

/** Whether a picked file or an upload is a photo; its content type says (anything else is a video). */
export function isPhoto(file: { contentType: string }): boolean {
  return file.contentType.startsWith('image/');
}

/**
 * Checks a picked file before any bytes move. Returns what to tell the user, or null when it may be
 * uploaded. The server checks again (the client can be wrong or bypassed); this only saves the user
 * an upload that is certain to be refused. On a phone a file over 1 GB is fine here: it is compressed
 * before it is sent. The browser cannot compress, so there it is refused.
 */
export function problemWith(video: PickedVideo, platform: string = Platform.OS): string | null {
  if (isPhoto(video)) {
    return problemWithPhoto(video);
  }
  if (!video.contentType.startsWith('video/')) {
    return 'Choose a video or a photo.';
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

/** A photo goes up as it is (no compression): it must already be a type and size the server keeps. */
function problemWithPhoto(photo: PickedVideo): string | null {
  if (!PHOTO_TYPES.includes(photo.contentType)) {
    return 'Choose a JPEG, PNG or WebP photo.';
  }
  if (photo.bytes <= 0) {
    return 'This file is empty.';
  }
  if (photo.bytes > MAX_PHOTO_BYTES) {
    return 'Photos can be up to 10 MB.';
  }
  return null;
}
