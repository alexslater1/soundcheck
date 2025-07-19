import { getThumbnailAsync } from 'expo-video-thumbnails';

import { File } from '@/lib/fileSystemNext';

/** An image ready to upload: where it is, its type and size. */
export type PreviewImage = {
  uri: string;
  contentType: string;
  bytes: number;
  width: number | null;
  height: number | null;
};

/**
 * A frame from a second into the clip, as a JPEG file: uploaded before the clip, it is what
 * the post shows, blurred, while the clip is on its way. Null when no frame can be made; posting never
 * waits for it.
 */
export async function previewFrame(videoUri: string): Promise<PreviewImage | null> {
  try {
    const frame = await getThumbnailAsync(videoUri, { time: 1000, quality: 0.5 });
    const bytes = new File(frame.uri).size;
    if (!bytes) {
      return null;
    }
    return {
      uri: frame.uri,
      contentType: 'image/jpeg',
      bytes,
      width: frame.width,
      height: frame.height,
    };
  } catch {
    return null;
  }
}
