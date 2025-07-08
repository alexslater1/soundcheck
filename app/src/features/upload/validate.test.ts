import type { PickedVideo } from './types';
import { MAX_BYTES, MAX_DURATION_MS, problemWith } from './validate';

const video = (overrides: Partial<PickedVideo> = {}): PickedVideo => ({
  uri: 'file:///clip.mp4',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 10_000_000,
  durationMs: 20_000,
  width: 720,
  height: 1280,
  ...overrides,
});

describe('problemWith (the upload limits)', () => {
  it('accepts a normal clip, a 3-minute one and one whose length is unknown', () => {
    expect(MAX_DURATION_MS).toBe(181_000);
    expect(problemWith(video(), 'android')).toBeNull();
    expect(problemWith(video({ durationMs: 180_400 }), 'android')).toBeNull();
    expect(problemWith(video({ durationMs: null }), 'android')).toBeNull();
  });

  it('refuses a clip longer than 3 minutes', () => {
    expect(problemWith(video({ durationMs: 181_001 }), 'android')).toBe(
      'Clips can be up to 3 minutes long.',
    );
  });

  it('refuses files that are not videos or empty', () => {
    expect(problemWith(video({ contentType: 'image/jpeg' }), 'android')).toBe(
      'Choose a video file.',
    );
    expect(problemWith(video({ bytes: 0 }), 'android')).toBe('This file is empty.');
  });

  it('takes a file over 1 GB on a phone (it is compressed first), not in the browser', () => {
    expect(MAX_BYTES).toBe(1024 * 1024 * 1024);
    expect(problemWith(video({ bytes: MAX_BYTES + 1 }), 'android')).toBeNull();
    expect(problemWith(video({ bytes: MAX_BYTES }), 'web')).toBeNull();
    expect(problemWith(video({ bytes: MAX_BYTES + 1 }), 'web')).toBe(
      'This video is larger than 1 GB.',
    );
  });
});
