import type { PickedVideo } from './types';
import { isPhoto, MAX_BYTES, MAX_DURATION_MS, MAX_PHOTO_BYTES, problemWith } from './validate';

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

  it('refuses files that are neither videos nor photos, or empty', () => {
    expect(problemWith(video({ contentType: 'application/pdf' }), 'android')).toBe(
      'Choose a video or a photo.',
    );
    expect(problemWith(video({ bytes: 0 }), 'android')).toBe('This file is empty.');
  });

  it('takes a JPEG, PNG or WebP photo of up to 10 MB, on a phone and in the browser', () => {
    const photo = (overrides: Partial<PickedVideo> = {}) =>
      video({ contentType: 'image/jpeg', durationMs: null, bytes: 4_000_000, ...overrides });
    expect(isPhoto(photo())).toBe(true);
    expect(isPhoto(video())).toBe(false);
    for (const contentType of ['image/jpeg', 'image/png', 'image/webp']) {
      expect(problemWith(photo({ contentType }), 'android')).toBeNull();
      expect(problemWith(photo({ contentType }), 'web')).toBeNull();
    }
    expect(problemWith(photo({ bytes: MAX_PHOTO_BYTES }), 'android')).toBeNull();
    expect(problemWith(photo({ bytes: MAX_PHOTO_BYTES + 1 }), 'android')).toBe(
      'Photos can be up to 10 MB.',
    );
    // A phone's HEIC is not a type the server keeps: refused before any upload.
    expect(problemWith(photo({ contentType: 'image/heic' }), 'android')).toBe(
      'Choose a JPEG, PNG or WebP photo.',
    );
    expect(problemWith(photo({ bytes: 0 }), 'android')).toBe('This file is empty.');
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
