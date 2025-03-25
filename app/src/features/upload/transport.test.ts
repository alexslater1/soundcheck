import { transport as nativeTransport } from './transport';

const mockHandle = { offset: 0 as number | null, readBytes: jest.fn(), close: jest.fn() };
const mockNativeFetch = jest.fn();

jest.mock('expo-file-system/next', () => ({
  File: jest.fn().mockImplementation(() => ({ open: () => mockHandle })),
}));
jest.mock('expo/fetch', () => ({ fetch: (...args: unknown[]) => mockNativeFetch(...args) }));

function response(status: number, etag?: string): Response {
  return new Response(null, { status, headers: etag ? { ETag: etag } : {} });
}

describe('native transport', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockHandle.offset = 0;
    mockHandle.readBytes.mockImplementation((length: number) => new Uint8Array(length));
  });

  it('reads exactly the part’s byte range and returns the ETag', async () => {
    mockNativeFetch.mockResolvedValue(response(200, '"abc"'));

    const etag = await nativeTransport.putPart(
      'https://s3.test/part-2',
      { uri: 'file:///clip.mp4', bytes: 2000 },
      1000,
      500,
    );

    expect(etag).toBe('"abc"');
    expect(mockHandle.offset).toBe(1000);
    expect(mockHandle.readBytes).toHaveBeenCalledWith(500);
    expect(mockHandle.close).toHaveBeenCalledTimes(1);
    const [url, init] = mockNativeFetch.mock.calls[0] as [
      string,
      { method: string; body: Uint8Array },
    ];
    expect(url).toBe('https://s3.test/part-2');
    expect(init.method).toBe('PUT');
    expect(init.body.byteLength).toBe(500);
  });

  it('sends the whole file with the signed headers', async () => {
    mockNativeFetch.mockResolvedValue(response(200));

    await nativeTransport.putWhole(
      'https://s3.test/whole',
      { 'Content-Type': 'video/mp4' },
      { uri: 'file:///clip.mp4', bytes: 1234 },
    );

    expect(mockHandle.readBytes).toHaveBeenCalledWith(1234);
    const [, init] = mockNativeFetch.mock.calls[0] as [string, { headers: Record<string, string> }];
    expect(init.headers).toEqual({ 'Content-Type': 'video/mp4' });
  });

  it('fails on a storage error and still closes the file', async () => {
    mockNativeFetch.mockResolvedValue(response(403));

    await expect(
      nativeTransport.putPart(
        'https://s3.test/part-1',
        { uri: 'file:///clip.mp4', bytes: 10 },
        0,
        10,
      ),
    ).rejects.toThrow('Storage answered 403');
    expect(mockHandle.close).toHaveBeenCalledTimes(1);
  });

  it('fails when storage returns no ETag (a CORS rule that does not expose it)', async () => {
    mockNativeFetch.mockResolvedValue(response(200));

    await expect(
      nativeTransport.putPart(
        'https://s3.test/part-1',
        { uri: 'file:///clip.mp4', bytes: 10 },
        0,
        10,
      ),
    ).rejects.toThrow('ETag');
  });
});
