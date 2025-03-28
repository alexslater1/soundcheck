import { chooseUpload } from './compressDecision';
import type { PickedVideo } from './types';

const original: PickedVideo = {
  uri: 'file:///original.mov',
  fileName: 'original.mov',
  contentType: 'video/quicktime',
  bytes: 60_000_000,
  durationMs: 20_000,
  width: 1920,
  height: 1080,
};

describe('chooseUpload', () => {
  it('uploads the compressed file and records both sizes and the time', () => {
    const { video, clientMeta, temporary, discard } = chooseUpload(
      original,
      { uri: 'file:///compressed.mp4', bytes: 18_000_000, elapsedMs: 4200 },
      'android',
    );

    expect(video).toMatchObject({
      uri: 'file:///compressed.mp4',
      bytes: 18_000_000,
      contentType: 'video/mp4',
      durationMs: 20_000,
    });
    expect(clientMeta).toEqual({
      platform: 'android',
      originalBytes: 60_000_000,
      compressedBytes: 18_000_000,
      compressMs: 4200,
    });
    // The compressed file is the app's own: it is deleted after the upload, not now.
    expect(temporary).toBe(true);
    expect(discard).toBeNull();
  });

  it('uploads the original when compression made the file bigger, and still records the attempt', () => {
    const { video, clientMeta, temporary, discard } = chooseUpload(
      original,
      { uri: 'file:///compressed.mp4', bytes: 61_000_000, elapsedMs: 3000 },
      'android',
    );

    expect(video).toBe(original);
    // The useless compressed file is thrown away at once; the user's original is never the app's to delete.
    expect(discard).toBe('file:///compressed.mp4');
    expect(temporary).toBe(false);
    expect(clientMeta).toMatchObject({ originalBytes: 60_000_000, compressedBytes: 61_000_000 });
  });

  it('uploads the original with only the platform when there was no compression (web)', () => {
    expect(chooseUpload(original, null, 'web')).toEqual({
      video: original,
      clientMeta: { platform: 'web' },
      temporary: false,
      discard: null,
    });
  });
});
