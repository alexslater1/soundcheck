import { contentHash, MAX_HASHED_BYTES } from './contentHash.web';

describe('content hash (browser)', () => {
  const realFetch = global.fetch;
  afterEach(() => {
    global.fetch = realFetch;
  });

  function serve(blob: Blob) {
    global.fetch = jest.fn(async () => ({ blob: async () => blob }) as Response) as typeof fetch;
  }

  it('is the SHA-256 of the picked file, prefixed', async () => {
    serve(new Blob([new TextEncoder().encode('abc')]));

    await expect(contentHash('blob:http://localhost/1')).resolves.toBe(
      'sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
  });

  it('is null over 200 MB, without reading the file', async () => {
    const big = { size: MAX_HASHED_BYTES + 1, arrayBuffer: jest.fn() } as unknown as Blob;
    serve(big);

    await expect(contentHash('blob:http://localhost/2')).resolves.toBeNull();
    expect(big.arrayBuffer).not.toHaveBeenCalled();
  });

  it('is null when the file cannot be fetched', async () => {
    global.fetch = jest.fn(async () => {
      throw new Error('revoked');
    }) as typeof fetch;

    await expect(contentHash('blob:http://localhost/3')).resolves.toBeNull();
  });
});
