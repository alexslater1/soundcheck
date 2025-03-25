import type { PickedVideo } from './types';
import { MAX_BYTES, problemWith } from './validate';

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

describe('problemWith', () => {
  it('accepts a normal clip, a 60-second one and one whose length is unknown', () => {
    expect(problemWith(video())).toBeNull();
    expect(problemWith(video({ durationMs: 60_400 }))).toBeNull();
    expect(problemWith(video({ durationMs: null }))).toBeNull();
  });

  it('refuses a clip longer than 60 seconds', () => {
    expect(problemWith(video({ durationMs: 61_001 }))).toBe('Clips can be up to 60 seconds long.');
  });

  it('refuses files that are not videos, empty, or over 500 MB', () => {
    expect(problemWith(video({ contentType: 'image/jpeg' }))).toBe('Choose a video file.');
    expect(problemWith(video({ bytes: 0 }))).toBe('This file is empty.');
    expect(problemWith(video({ bytes: MAX_BYTES + 1 }))).toBe('This video is larger than 500 MB.');
  });
});
