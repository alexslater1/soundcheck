import type { ImagePickerAsset } from 'expo-image-picker';

import { result, toPicked } from './pickVideo';

jest.mock('expo-image-picker', () => ({}));

const asset = (overrides: Partial<ImagePickerAsset> = {}): ImagePickerAsset => ({
  uri: 'file:///DCIM/clip.mp4',
  width: 1080,
  height: 1920,
  fileName: 'clip.mp4',
  fileSize: 42_000_000,
  mimeType: 'video/mp4',
  duration: 20_500,
  ...overrides,
});

describe('toPicked', () => {
  it('keeps Android’s milliseconds', () => {
    expect(toPicked(asset(), 'android')).toEqual({
      uri: 'file:///DCIM/clip.mp4',
      fileName: 'clip.mp4',
      contentType: 'video/mp4',
      bytes: 42_000_000,
      durationMs: 20_500,
      width: 1080,
      height: 1920,
    });
  });

  it('converts the web picker’s seconds to milliseconds', () => {
    expect(
      toPicked(asset({ uri: 'blob:http://localhost/1', duration: 20.5 }), 'web').durationMs,
    ).toBe(20_500);
  });

  it('treats unreadable metadata as unknown rather than zero', () => {
    const picked = toPicked(
      asset({ duration: 0, width: 0, height: 0, mimeType: undefined, fileName: null }),
      'web',
    );
    expect(picked).toMatchObject({
      durationMs: null,
      width: null,
      height: null,
      contentType: 'video/mp4',
      fileName: null,
    });
  });
});

describe('result', () => {
  it('returns every picked video in order, or cancelled', () => {
    const picked = result(
      {
        canceled: false,
        assets: [asset({ uri: 'file:///a.mp4' }), asset({ uri: 'file:///b.mp4' })],
      },
      'android',
    );
    expect(picked.kind === 'picked' && picked.videos.map((v) => v.uri)).toEqual([
      'file:///a.mp4',
      'file:///b.mp4',
    ]);
    expect(result({ canceled: true, assets: null }, 'android')).toEqual({ kind: 'cancelled' });
  });
});
