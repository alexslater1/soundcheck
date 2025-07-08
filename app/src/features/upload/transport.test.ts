import { COPY_CHUNK, transport as nativeTransport } from './transport';

/** A fake file system: each file is a byte array; handles read and write at their offset. */
const mockFiles = new Map<string, Uint8Array>();
const mockUpload = jest.fn();
const mockReads: number[] = [];

jest.mock('expo-file-system', () => ({
  uploadAsync: (url: string, uri: string, options: unknown) =>
    mockUpload(uri, url, options, mockFiles.get(uri)),
}));
jest.mock('expo-file-system/next', () => {
  class MockFile {
    uri: string;
    constructor(...parts: (string | { uri: string })[]) {
      this.uri = parts.map((p) => (typeof p === 'string' ? p : p.uri)).join('/');
    }
    create() {
      mockFiles.set(this.uri, new Uint8Array(0));
    }
    get exists() {
      return mockFiles.has(this.uri);
    }
    delete() {
      mockFiles.delete(this.uri);
    }
    open() {
      const uri = this.uri;
      return {
        offset: 0 as number | null,
        get size() {
          return (mockFiles.get(uri) ?? new Uint8Array(0)).byteLength;
        },
        readBytes(length: number) {
          const all = mockFiles.get(uri) ?? new Uint8Array(0);
          mockReads.push(length);
          const start = this.offset ?? 0;
          const bytes = all.slice(start, start + length);
          this.offset = start + bytes.byteLength;
          return bytes;
        },
        writeBytes(bytes: Uint8Array) {
          const all = mockFiles.get(uri) ?? new Uint8Array(0);
          const next = new Uint8Array(all.byteLength + bytes.byteLength);
          next.set(all);
          next.set(bytes, all.byteLength);
          mockFiles.set(uri, next);
        },
        close: jest.fn(),
      };
    }
  }
  return { File: MockFile, Paths: { cache: { uri: 'file:///cache' } } };
});

const SOURCE = 'file:///clip.mp4';

function video(bytes: number): Uint8Array {
  return Uint8Array.from({ length: bytes }, (_, i) => i % 251);
}

function partFiles(): string[] {
  return [...mockFiles.keys()].filter((uri) => uri.startsWith('file:///cache/'));
}

describe('native transport', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockFiles.clear();
    mockReads.length = 0;
  });

  it('uploads exactly the part’s byte range from a temporary file, deletes it and returns the ETag', async () => {
    mockFiles.set(SOURCE, video(5000));
    let sent: Uint8Array | undefined;
    mockUpload.mockImplementation((_uri: string, _url: string, _o: unknown, body: Uint8Array) => {
      sent = body;
      return Promise.resolve({ status: 200, body: '', headers: { etag: '"abc"' } });
    });

    const etag = await nativeTransport.putPart(
      'https://s3.test/part-2',
      { uri: SOURCE, bytes: 5000 },
      1000,
      500,
    );

    expect(etag).toBe('"abc"');
    expect(sent).toEqual(video(5000).slice(1000, 1500));
    const [uri, url, options] = mockUpload.mock.calls[0] as [string, string, object];
    expect(uri).toMatch(/^file:\/\/\/cache\/upload-part-/);
    expect(url).toBe('https://s3.test/part-2');
    expect(options).toEqual({ httpMethod: 'PUT', headers: {} });
    expect(partFiles()).toEqual([]);
  });

  it('never reads more than 1 MiB of the video at once', async () => {
    const bytes = 2 * COPY_CHUNK + 10;
    mockFiles.set(SOURCE, video(bytes));
    mockUpload.mockResolvedValue({ status: 200, body: '', headers: { ETag: '"x"' } });

    await nativeTransport.putPart('https://s3.test/p', { uri: SOURCE, bytes }, 0, bytes);

    expect(Math.max(...mockReads)).toBe(COPY_CHUNK);
    expect(mockReads).toEqual([COPY_CHUNK, COPY_CHUNK, 10]);
  });

  it('sends the whole file natively, with the signed headers and no copy', async () => {
    mockFiles.set(SOURCE, video(1234));
    mockUpload.mockResolvedValue({ status: 200, body: '', headers: {} });

    await nativeTransport.putWhole(
      'https://s3.test/whole',
      { 'Content-Type': 'video/mp4' },
      { uri: SOURCE, bytes: 1234 },
    );

    const [uri, , options] = mockUpload.mock.calls[0] as [string, string, object];
    expect(uri).toBe(SOURCE);
    expect(options).toEqual({ httpMethod: 'PUT', headers: { 'Content-Type': 'video/mp4' } });
    expect(mockReads).toEqual([]);
  });

  it('fails on a storage error and still deletes the temporary file', async () => {
    mockFiles.set(SOURCE, video(10));
    mockUpload.mockResolvedValue({ status: 403, body: 'denied', headers: {} });

    await expect(
      nativeTransport.putPart('https://s3.test/part-1', { uri: SOURCE, bytes: 10 }, 0, 10),
    ).rejects.toThrow('Storage answered 403');
    expect(partFiles()).toEqual([]);
  });

  it('fails when the network fails and still deletes the temporary file', async () => {
    mockFiles.set(SOURCE, video(10));
    mockUpload.mockRejectedValue(new Error('Upload failed'));

    await expect(
      nativeTransport.putPart('https://s3.test/part-1', { uri: SOURCE, bytes: 10 }, 0, 10),
    ).rejects.toThrow('Upload failed');
    expect(partFiles()).toEqual([]);
  });

  it('fails, leaving no temporary file, when the video is shorter than the part', async () => {
    mockFiles.set(SOURCE, video(10));

    await expect(
      nativeTransport.putPart('https://s3.test/part-1', { uri: SOURCE, bytes: 20 }, 0, 20),
    ).rejects.toThrow('shorter than expected');
    expect(mockUpload).not.toHaveBeenCalled();
    expect(partFiles()).toEqual([]);
  });

  it('fails when storage returns no ETag (a CORS rule that does not expose it)', async () => {
    mockFiles.set(SOURCE, video(10));
    mockUpload.mockResolvedValue({ status: 200, body: '', headers: {} });

    await expect(
      nativeTransport.putPart('https://s3.test/part-1', { uri: SOURCE, bytes: 10 }, 0, 10),
    ).rejects.toThrow('ETag');
  });
});
