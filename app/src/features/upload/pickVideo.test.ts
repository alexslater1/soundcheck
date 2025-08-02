import type { ImagePickerAsset } from 'expo-image-picker';

import { pickFromLibrary, recordWithCamera, result, toPicked } from './pickVideo';

const mockLaunchCamera = jest.fn();
const mockLaunchLibrary = jest.fn();
jest.mock('expo-image-picker', () => ({
  requestCameraPermissionsAsync: async () => ({ granted: true }),
  launchCameraAsync: (options: unknown) => mockLaunchCamera(options),
  launchImageLibraryAsync: (options: unknown) => mockLaunchLibrary(options),
}));

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

describe('toPicked for a photo', () => {
  it('keeps its type and size and gives it no length', () => {
    expect(
      toPicked(
        asset({
          type: 'image',
          uri: 'file:///DCIM/crowd.jpg',
          fileName: 'crowd.jpg',
          mimeType: 'image/jpeg',
          fileSize: 3_000_000,
          width: 4000,
          height: 3000,
          duration: null,
        }),
        'android',
      ),
    ).toEqual({
      uri: 'file:///DCIM/crowd.jpg',
      fileName: 'crowd.jpg',
      contentType: 'image/jpeg',
      bytes: 3_000_000,
      durationMs: null,
      width: 4000,
      height: 3000,
    });
  });

  it('calls a photo with no reported type a JPEG, not a video', () => {
    expect(toPicked(asset({ type: 'image', mimeType: undefined }), 'web')).toMatchObject({
      contentType: 'image/jpeg',
      durationMs: null,
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

describe('recordWithCamera', () => {
  it('lets the camera record up to 3 minutes', async () => {
    mockLaunchCamera.mockResolvedValue({ canceled: true, assets: null });

    expect(await recordWithCamera()).toEqual({ kind: 'cancelled' });
    expect(mockLaunchCamera).toHaveBeenCalledWith(
      expect.objectContaining({ mediaTypes: ['videos'], videoMaxDuration: 180 }),
    );
  });
});

describe('pickFromLibrary', () => {
  it('offers photos as well as videos, several at once, as stored', async () => {
    mockLaunchLibrary.mockResolvedValue({ canceled: true, assets: null });

    expect(await pickFromLibrary()).toEqual({ kind: 'cancelled' });
    const options = mockLaunchLibrary.mock.calls[0]![0] as Record<string, unknown>;
    expect(options).toMatchObject({
      mediaTypes: ['images', 'videos'],
      allowsMultipleSelection: true,
      selectionLimit: 10,
    });
    // Quality 1 (the default): the picker hands over the file itself, so its size is what is sent.
    expect(options.quality).toBeUndefined();
  });
});
