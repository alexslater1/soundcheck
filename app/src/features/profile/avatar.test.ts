import type * as ImagePicker from 'expo-image-picker';

import { setAvatar, toPickedImage } from './avatar';

const mockCreateUpload = jest.fn();
const mockCompleteUpload = jest.fn();
const mockUpdateMe = jest.fn();
const mockPutWhole = jest.fn();

jest.mock('expo-image-picker', () => ({ launchImageLibraryAsync: jest.fn() }));
jest.mock('@/api/generated/media/media', () => ({
  createUpload: (...args: unknown[]) => mockCreateUpload(...args),
  completeUpload: (...args: unknown[]) => mockCompleteUpload(...args),
}));
jest.mock('@/api/generated/me/me', () => ({
  updateMe: (...args: unknown[]) => mockUpdateMe(...args),
}));
jest.mock('@/features/upload/transport', () => ({
  transport: { putWhole: (...args: unknown[]) => mockPutWhole(...args) },
}));

const asset = (over: Partial<ImagePicker.ImagePickerAsset>) =>
  ({
    uri: 'file:///photo.jpg',
    width: 800,
    height: 800,
    mimeType: 'image/jpeg',
    fileSize: 120_000,
    ...over,
  }) as ImagePicker.ImagePickerAsset;

describe('toPickedImage', () => {
  it('keeps what the upload needs', () => {
    expect(toPickedImage(asset({}))).toEqual({
      uri: 'file:///photo.jpg',
      contentType: 'image/jpeg',
      bytes: 120_000,
      width: 800,
      height: 800,
    });
  });

  it('refuses a type the server does not keep, or an unknown size', () => {
    expect(toPickedImage(asset({ mimeType: 'image/heic' }))).toBeNull();
    expect(toPickedImage(asset({ fileSize: undefined }))).toBeNull();
  });
});

describe('setAvatar', () => {
  beforeEach(() => jest.clearAllMocks());

  it('uploads the photo with one PUT, completes it, then makes it the profile photo', async () => {
    mockCreateUpload.mockResolvedValue({
      mediaId: 'm1',
      strategy: 'SINGLE',
      putUrl: 'https://storage/put',
      headers: { 'Content-Type': 'image/jpeg' },
      expiresAt: '2026-10-03T13:00:00Z',
    });
    mockCompleteUpload.mockResolvedValue({ status: 'READY' });
    mockUpdateMe.mockResolvedValue({ id: 'me' });
    const image = toPickedImage(asset({}))!;

    await setAvatar(image);

    expect(mockCreateUpload).toHaveBeenCalledWith({
      kind: 'IMAGE',
      contentType: 'image/jpeg',
      bytes: 120_000,
      width: 800,
      height: 800,
    });
    expect(mockPutWhole).toHaveBeenCalledWith(
      'https://storage/put',
      { 'Content-Type': 'image/jpeg' },
      { uri: 'file:///photo.jpg', bytes: 120_000 },
    );
    expect(mockCompleteUpload).toHaveBeenCalledWith('m1', {});
    expect(mockUpdateMe).toHaveBeenCalledWith({ avatarMediaId: 'm1' });
    // In that order: the profile points at the photo only once it is stored and checked.
    expect(mockPutWhole.mock.invocationCallOrder[0]).toBeLessThan(
      mockCompleteUpload.mock.invocationCallOrder[0]!,
    );
    expect(mockCompleteUpload.mock.invocationCallOrder[0]).toBeLessThan(
      mockUpdateMe.mock.invocationCallOrder[0]!,
    );
  });

  it('changes nothing on the profile when the PUT fails', async () => {
    mockCreateUpload.mockResolvedValue({
      mediaId: 'm2',
      strategy: 'SINGLE',
      putUrl: 'https://storage/put',
      expiresAt: '2026-10-03T13:00:00Z',
    });
    mockPutWhole.mockRejectedValue(new Error('Storage answered 403'));

    await expect(setAvatar(toPickedImage(asset({}))!)).rejects.toThrow('403');
    expect(mockCompleteUpload).not.toHaveBeenCalled();
    expect(mockUpdateMe).not.toHaveBeenCalled();
  });
});
