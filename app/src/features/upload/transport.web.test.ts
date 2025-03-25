import { transport as webTransport } from './transport.web';

type Call = { url: string; method?: string; headers?: Record<string, string>; size: number };

function response(status: number, etag?: string): Response {
  return new Response(null, { status, headers: etag ? { ETag: etag } : {} });
}

describe('web transport', () => {
  const realFetch = global.fetch;
  const calls: Call[] = [];
  const file = new Blob([new Uint8Array(2000)]);

  beforeEach(() => {
    calls.length = 0;
    global.fetch = jest.fn(async (input: unknown, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith('blob:')) {
        return { blob: async () => file } as Response;
      }
      calls.push({
        url,
        method: init?.method,
        headers: init?.headers as Record<string, string>,
        size: (init?.body as Blob).size,
      });
      return response(url.includes('forbidden') ? 403 : 200, '"web-etag"');
    }) as typeof fetch;
  });
  afterEach(() => {
    global.fetch = realFetch;
  });

  it('slices the blob for a part and returns the ETag', async () => {
    const etag = await webTransport.putPart(
      'https://s3.test/part-2',
      { uri: 'blob:http://localhost/1', bytes: 2000 },
      1500,
      500,
    );

    expect(etag).toBe('"web-etag"');
    expect(calls).toEqual([
      { url: 'https://s3.test/part-2', method: 'PUT', headers: {}, size: 500 },
    ]);
  });

  it('sends the whole blob with the signed headers, and fails on a storage error', async () => {
    const source = { uri: 'blob:http://localhost/1', bytes: 2000 };
    await webTransport.putWhole('https://s3.test/whole', { 'Content-Type': 'video/mp4' }, source);
    expect(calls[0]).toEqual({
      url: 'https://s3.test/whole',
      method: 'PUT',
      headers: { 'Content-Type': 'video/mp4' },
      size: 2000,
    });

    await expect(webTransport.putWhole('https://s3.test/forbidden', {}, source)).rejects.toThrow(
      'Storage answered 403',
    );
  });
});
