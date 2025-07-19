import type { PreviewImage } from './previewFrame';

export type { PreviewImage } from './previewFrame';

/** The longest side of the preview: it is shown blurred, so a small image is enough. */
const MAX_SIDE = 480;

/** How long the browser gets to load a frame before the preview is given up. */
const TIMEOUT_MS = 5000;

function withTimeout<T>(work: Promise<T>): Promise<T> {
  return Promise.race([
    work,
    new Promise<never>((_, reject) => setTimeout(() => reject(new Error('timeout')), TIMEOUT_MS)),
  ]);
}

/**
 * The browser's frame of the clip: the video is loaded into an off-screen element, a second in
 * (or halfway, for a shorter clip), drawn onto a canvas at most {@link MAX_SIDE} pixels across and saved
 * as a JPEG blob. Null when the browser cannot decode it or takes too long; posting never waits for it.
 */
export async function previewFrame(videoUri: string): Promise<PreviewImage | null> {
  try {
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.preload = 'auto';
    video.src = videoUri;
    await withTimeout(
      new Promise<void>((resolve, reject) => {
        video.onloadeddata = () => resolve();
        video.onerror = () => reject(new Error('decode'));
      }),
    );
    await withTimeout(
      new Promise<void>((resolve) => {
        video.onseeked = () => resolve();
        video.currentTime = Math.min(1, (video.duration || 2) / 2);
      }),
    );
    const scale = Math.min(1, MAX_SIDE / Math.max(video.videoWidth, video.videoHeight, 1));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(video.videoWidth * scale));
    canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
    canvas.getContext('2d')?.drawImage(video, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', 0.6),
    );
    if (!blob) {
      return null;
    }
    return {
      uri: URL.createObjectURL(blob),
      contentType: 'image/jpeg',
      bytes: blob.size,
      width: canvas.width,
      height: canvas.height,
    };
  } catch {
    return null;
  }
}
