import {
  chooseUpload,
  CLIENT_MAX_LONG_EDGE,
  CLIENT_TARGET_BITRATE,
  compressionPlan,
  sourceBitrate,
} from './compressDecision';
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

describe('compressionPlan', () => {
  const video = (
    bytes: number,
    durationMs: number | null,
    width: number | null,
    height: number | null,
  ) => ({
    ...original,
    bytes,
    durationMs,
    width,
    height,
  });

  it('compresses a typical phone recording to 1080p at 8 Mbit/s', () => {
    // 20 s of 1080p30 from a Pixel: about 17 Mbit/s.
    expect(compressionPlan(video(42_500_000, 20_000, 1920, 1080))).toEqual({
      maxSize: CLIENT_MAX_LONG_EDGE,
      bitrate: CLIENT_TARGET_BITRATE,
    });
  });

  it('compresses 4K even at a modest bitrate (the frame is bigger than the server ever uses)', () => {
    expect(compressionPlan(video(20_000_000, 20_000, 3840, 2160))).not.toBeNull();
  });

  it('leaves an already efficient 1080p clip alone', () => {
    // 20 s at 6 Mbit/s: re-encoding would lose quality and save almost nothing.
    expect(compressionPlan(video(15_000_000, 20_000, 1080, 1920))).toBeNull();
  });

  it('compresses when the duration is unknown (the safe side for the data plan)', () => {
    expect(compressionPlan(video(15_000_000, null, 1080, 1920))).not.toBeNull();
  });

  it('works the bitrate out from size and duration', () => {
    expect(sourceBitrate(video(10_000_000, 10_000, null, null))).toBe(8_000_000);
    expect(sourceBitrate(video(10_000_000, 0, null, null))).toBeNull();
  });
});
