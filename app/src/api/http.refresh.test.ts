import {
  ApiError,
  customFetch,
  EXPIRY_MARGIN_MS,
  setAccessTokenProvider,
  setRefreshHandler,
} from './http';

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
  setAccessTokenProvider(() => ({ token: 'stale' }));
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
    setAccessTokenProvider(() => ({ token: 'fresh' }));
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

  it('a 401 for a token another request has already replaced is retried with the new one, without refreshing again', async () => {
    let current = 'old';
    setAccessTokenProvider(() => ({ token: current }));
    const refresh = jest.fn(async () => {
      current = 'new';
      return 'new';
    });
    setRefreshHandler(refresh);
    // /b's 401 (sent with the old token) arrives only after /a's refresh has finished.
    let answerB: () => void = () => {};
    const bAnswered = new Promise<void>((resolve) => (answerB = resolve));
    fetchMock.mockImplementation(async (url, init) => {
      if (authHeader([url, init]) === 'Bearer new') return json(200, { ok: true });
      if (String(url).endsWith('/b')) await bAnswered;
      return json(401, { status: 401, code: 'UNAUTHENTICATED' });
    });

    const b = customFetch<{ ok: boolean }>('/api/v1/b');
    await expect(customFetch<{ ok: boolean }>('/api/v1/a')).resolves.toEqual({ ok: true });
    answerB();

    await expect(b).resolves.toEqual({ ok: true });
    // One rotation, not two: every extra refresh is one more chance to present a rotated token.
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('a refresh the server could not answer keeps the session: the request fails with that error, not a 401', async () => {
    const offline = new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server');
    const refresh = jest.fn(async (): Promise<string | null> => {
      throw offline;
    });
    setRefreshHandler(refresh);
    fetchMock.mockResolvedValue(json(401, { status: 401, code: 'UNAUTHENTICATED' }));

    const error = await customFetch('/api/v1/me').catch((e: unknown) => e);

    expect(error).toBe(offline);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('every request waiting on a refresh that fails gets its error, and the next request tries again', async () => {
    let fail = true;
    const refresh = jest.fn(async () => {
      if (fail) throw new ApiError(502, 'HTTP_502', 'Bad Gateway');
      return 'fresh';
    });
    setRefreshHandler(refresh);
    fetchMock.mockImplementation(async (url, init) =>
      authHeader([url, init]) === 'Bearer fresh'
        ? json(200, { ok: true })
        : json(401, { status: 401, code: 'UNAUTHENTICATED' }),
    );

    const failed = await Promise.allSettled([customFetch('/api/v1/a'), customFetch('/api/v1/b')]);
    expect(failed.map((r) => r.status)).toEqual(['rejected', 'rejected']);
    expect(refresh).toHaveBeenCalledTimes(1);

    fail = false;
    await expect(customFetch('/api/v1/a')).resolves.toEqual({ ok: true });
    expect(refresh).toHaveBeenCalledTimes(2);
  });
});

describe('back from the background with an expired access token', () => {
  beforeEach(() => {
    jest.useFakeTimers({ now: new Date('2026-10-03T12:00:00Z') });
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('refreshes once before sending, so the requests on screen never collect a 401', async () => {
    let current = { token: 'old', expiresAt: Date.now() + 15 * 60_000 };
    setAccessTokenProvider(() => current);
    const refresh = jest.fn(async () => {
      current = { token: 'new', expiresAt: Date.now() + 15 * 60_000 };
      return 'new';
    });
    setRefreshHandler(refresh);
    fetchMock.mockImplementation(async (url, init) =>
      authHeader([url, init]) === 'Bearer new'
        ? json(200, { ok: true })
        : json(401, { status: 401, code: 'UNAUTHENTICATED' }),
    );

    // The phone was put down for 20 minutes: the 15-minute token has run out meanwhile.
    jest.setSystemTime(Date.now() + 20 * 60_000);
    const results = await Promise.all([
      customFetch('/api/v1/feed'),
      customFetch('/api/v1/me'),
      customFetch('/api/v1/notifications'),
    ]);

    expect(results).toEqual([{ ok: true }, { ok: true }, { ok: true }]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls.every((c) => authHeader(c) === 'Bearer new')).toBe(true);
  });

  it(`refreshes a token with less than ${EXPIRY_MARGIN_MS / 1000} s left, and leaves a fresher one alone`, async () => {
    const expiresAt = Date.now() + EXPIRY_MARGIN_MS + 1_000;
    setAccessTokenProvider(() => ({ token: 'live', expiresAt }));
    const refresh = jest.fn(async () => 'new');
    setRefreshHandler(refresh);
    fetchMock.mockImplementation(async () => json(200, { ok: true }));

    await customFetch('/api/v1/feed');
    expect(refresh).not.toHaveBeenCalled();

    jest.setSystemTime(Date.now() + 2_000);
    await customFetch('/api/v1/feed');
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(authHeader(fetchMock.mock.calls[1]!)).toBe('Bearer new');
  });

  it('sends nothing when that refresh cannot reach the server', async () => {
    setAccessTokenProvider(() => ({ token: 'old', expiresAt: Date.now() - 1 }));
    const offline = new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server');
    setRefreshHandler(async () => {
      throw offline;
    });

    await expect(customFetch('/api/v1/feed')).rejects.toBe(offline);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
