import { contentHash } from './contentHash';

const mockInfo = jest.fn();
jest.mock('expo-file-system', () => ({
  getInfoAsync: async (uri: string, options: { md5?: boolean }) => mockInfo(uri, options),
}));

describe('content hash (Android)', () => {
  beforeEach(() => mockInfo.mockReset());

  it('is the native MD5 of the file, prefixed and in lower case', async () => {
    mockInfo.mockReturnValue({ exists: true, md5: 'D41D8CD98F00B204E9800998ECF8427E' });

    await expect(contentHash('file:///picked.mp4')).resolves.toBe(
      'md5:d41d8cd98f00b204e9800998ecf8427e',
    );
    expect(mockInfo).toHaveBeenCalledWith('file:///picked.mp4', { md5: true });
  });

  it('is null when the file cannot be read or gives no hash: the upload goes ahead without it', async () => {
    mockInfo.mockImplementationOnce(() => {
      throw new Error('no permission');
    });
    await expect(contentHash('file:///gone.mp4')).resolves.toBeNull();
    mockInfo.mockReturnValueOnce({ exists: true });
    await expect(contentHash('file:///odd.mp4')).resolves.toBeNull();
  });
});
