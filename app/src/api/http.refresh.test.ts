import { ApiError, customFetch, setAccessTokenProvider, setRefreshHandler } from './http';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function authHeader(call: [RequestInfo | URL, RequestInit | undefined]): string | null {
  return new Headers(call[1]?.headers).get('Authorization');
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  setAccessTokenProvider(() => 'stale');
  setRefreshHandler(null);
});

describe('customFetch refresh-and-retry (single-flight)', () => {
  it('three concurrent 401s perform exactly one refresh and each request is retried once', async () => {
    let resolveRefresh: (token: string | null) => void = () => {};
    const refresh = jest.fn(
      () => new Promise<string | null>((resolve) => (resolveRefresh = resolve)),
    );
    setRefreshHandler(refresh);
    // First attempt of each request: 401 with the stale token; retries with the fresh token: 200.
    fetchMock.mockImplementation(async (_url, init) => {
      const auth = new Headers(init?.headers).get('Authorization');
      return auth === 'Bearer fresh'
        ? json(200, { ok: true })
        : json(401, { status: 401, code: 'UNAUTHENTICATED' });
    });

    const requests = Promise.all([
      customFetch<{ ok: boolean }>('/api/v1/a'),
      customFetch<{ ok: boolean }>('/api/v1/b'),
      customFetch<{ ok: boolean }>('/api/v1/c'),
    ]);
    // Let the three first attempts fail and queue on the single refresh.
    await new Promise((r) => setTimeout(r, 0));
    expect(refresh).toHaveBeenCalledTimes(1);
    setAccessTokenProvider(() => 'fresh');
    resolveRefresh('fresh');

    const results = await requests;

    expect(results).toEqual([{ ok: true }, { ok: true }, { ok: true }]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(6);
    const retries = fetchMock.mock.calls.filter((c) => authHeader(c) === 'Bearer fresh');
    expect(retries).toHaveLength(3);
  });

  it('propagates the 401 and does not retry when the refresh fails', async () => {
    const refresh = jest.fn(async () => null);
    setRefreshHandler(refresh);
    fetchMock.mockResolvedValue(json(401, { status: 401, code: 'UNAUTHENTICATED' }));

    await expect(customFetch('/api/v1/me')).rejects.toMatchObject({ status: 401 });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('never refreshes for the auth endpoints themselves (skipRefresh)', async () => {
    const refresh = jest.fn(async () => 'fresh');
    setRefreshHandler(refresh);
    fetchMock.mockResolvedValue(json(401, { status: 401, code: 'INVALID_CREDENTIALS' }));

    const error = await customFetch('/api/v1/auth/login', {
      method: 'POST',
      body: '{}',
      skipRefresh: true,
    }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('INVALID_CREDENTIALS');
    expect(refresh).not.toHaveBeenCalled();
  });
});
