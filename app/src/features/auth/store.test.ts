import { ApiError, customFetch, refreshOnce } from '@/api/http';
import { queryClient } from '@/api/queryClient';
import { refreshTokenStore } from '@/lib/secureStore';

import {
  BOOTSTRAP_ATTEMPTS,
  beforeSignOut,
  REFRESH_ATTEMPTS,
  refreshSession,
  useAuthStore,
} from './store';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

const session = {
  accessToken: 'access-1',
  expiresIn: 900,
  refreshToken: 'refresh-1',
  user: { id: 'u1', handle: 'fan', displayName: 'Fan', avatarUrl: null },
};
const me = {
  id: 'u1',
  handle: 'fan',
  displayName: 'Fan',
  avatarUrl: null,
  email: 'fan@example.com',
  bio: null,
  homeCity: null,
  genres: [],
  role: 'USER',
  onboarded: false,
  counts: { followers: 0, following: 0, posts: 0 },
};

afterEach(() => {
  jest.restoreAllMocks();
});

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  // Forgets the refresh token the previous test's session kept in memory.
  useAuthStore.getState().clear();
  useAuthStore.setState({
    status: 'unknown',
    accessToken: null,
    accessTokenExpiresAt: null,
    me: null,
  });
});

/** Answers /auth/refresh with `refresh` and everything else (/me) with the profile. */
function serveRefresh(refresh: () => Response | Promise<Response>): void {
  fetchMock.mockImplementation(async (url) =>
    String(url).endsWith('/auth/refresh') ? refresh() : json(200, me),
  );
}

function refreshCalls(): string[] {
  return fetchMock.mock.calls
    .filter((c) => String(c[0]).endsWith('/auth/refresh'))
    .map((c) => (JSON.parse(String(c[1]?.body)) as { refreshToken: string }).refreshToken);
}

describe('auth store', () => {
  it('bootstrap ends unreachable (not stuck, not signed out) when /me cannot be loaded after a refresh', async () => {
    jest.useFakeTimers();
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue('refresh-1');
    const saved = jest.spyOn(refreshTokenStore, 'set');
    fetchMock.mockImplementation(async (url) =>
      String(url).endsWith('/auth/refresh')
        ? json(200, session)
        : Promise.reject(new TypeError('offline')),
    );

    const started = useAuthStore.getState().bootstrap();
    await jest.runAllTimersAsync();
    await started;
    jest.useRealTimers();

    expect(useAuthStore.getState().status).toBe('unreachable');
    expect(saved).not.toHaveBeenCalledWith(null);
  });

  it('signIn stores the access token in memory, with when it runs out, and loads /me', async () => {
    fetchMock.mockImplementation(async (url) =>
      String(url).endsWith('/auth/login') ? json(200, session) : json(200, me),
    );

    await useAuthStore.getState().signIn('fan@example.com', 'correct horse battery');

    const state = useAuthStore.getState();
    expect(state.status).toBe('signedIn');
    expect(state.accessToken).toBe('access-1');
    expect(state.accessTokenExpiresAt).toBeGreaterThan(Date.now() + 890_000);
    expect(state.accessTokenExpiresAt).toBeLessThanOrEqual(Date.now() + 900_000);
    expect(state.me?.handle).toBe('fan');
    // /me carried the bearer token; the login call did not.
    const meCall = fetchMock.mock.calls.find((c) => String(c[0]).endsWith('/me'));
    expect(new Headers(meCall?.[1]?.headers).get('Authorization')).toBe('Bearer access-1');
  });

  it('signOut calls logout and clears state even when the server rejects it', async () => {
    useAuthStore.setState({ status: 'signedIn', accessToken: 'access-1', me: me as never });
    fetchMock.mockResolvedValue(json(401, { status: 401, code: 'REFRESH_INVALID' }));

    await useAuthStore.getState().signOut();

    const state = useAuthStore.getState();
    expect(state.status).toBe('signedOut');
    expect(state.accessToken).toBeNull();
    expect(state.me).toBeNull();
    expect(String(fetchMock.mock.calls[0]![0])).toContain('/auth/logout');
  });

  it('runs the sign-out hooks first, while the session still works, and a failing one blocks nothing', async () => {
    const seen: (string | null)[] = [];
    beforeSignOut(async () => {
      seen.push(useAuthStore.getState().accessToken);
    });
    beforeSignOut(() => Promise.reject(new Error('offline')));
    useAuthStore.setState({ status: 'signedIn', accessToken: 'access-1', me: me as never });
    fetchMock.mockResolvedValue(json(200, {}));

    await useAuthStore.getState().signOut();

    expect(seen).toEqual(['access-1']);
    expect(useAuthStore.getState().status).toBe('signedOut');
  });

  it('refreshSession clears the session when the refresh token is gone', async () => {
    useAuthStore.setState({ status: 'signedIn', accessToken: 'stale', me: me as never });
    // Android with no refresh token in memory or stored: nothing can ever renew this session, and no
    // request is even attempted.
    expect(await refreshSession()).toBeNull();
    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('drops every cached response when the session ends', async () => {
    queryClient.setQueryData(['/api/v1/gigs/g1'], { gig: { myAttendance: 'GOING' } });
    queryClient.setQueryData(['/api/v1/artists/a1'], { artist: { isFollowing: true } });

    useAuthStore.getState().clear();

    expect(queryClient.getQueryData(['/api/v1/gigs/g1'])).toBeUndefined();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(useAuthStore.getState().status).toBe('signedOut');
  });
});

describe('a rate-limited refresh', () => {
  function limited(retryAfter: string): Response {
    return new Response(JSON.stringify({ status: 429, code: 'RATE_LIMITED' }), {
      status: 429,
      headers: { 'content-type': 'application/problem+json', 'Retry-After': retryAfter },
    });
  }

  /** Answers /auth/refresh from the list in order and everything else (/me) with the profile. */
  function serve(refreshAnswers: Response[]): void {
    fetchMock.mockImplementation(async (url) =>
      String(url).endsWith('/auth/refresh') ? refreshAnswers.shift()! : json(200, me),
    );
  }

  beforeEach(() => {
    jest.useFakeTimers();
    const { refreshTokenStore } =
      jest.requireActual<typeof import('@/lib/secureStore')>('@/lib/secureStore');
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue('refresh-1');
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('waits the Retry-After the server gave, retries, and stays signed in', async () => {
    serve([limited('3'), json(200, session)]);

    const result = refreshSession();
    await jest.advanceTimersByTimeAsync(2_999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await jest.advanceTimersByTimeAsync(1);

    await expect(result).resolves.toBe('access-1');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(useAuthStore.getState().status).toBe('signedIn');
  });

  it('waits at most 30 seconds, however long the server asks', async () => {
    serve([limited('600'), json(200, session)]);

    const result = refreshSession();
    await jest.advanceTimersByTimeAsync(29_999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await jest.advanceTimersByTimeAsync(1);

    await expect(result).resolves.toBe('access-1');
  });

  it('lets the app start signed in instead of on the sign-in screen', async () => {
    serve([limited('1'), json(200, session)]);

    const started = useAuthStore.getState().bootstrap();
    await jest.advanceTimersByTimeAsync(1_000);
    await started;

    expect(useAuthStore.getState().status).toBe('signedIn');
    expect(useAuthStore.getState().me?.handle).toBe('fan');
  });

  it(`stops after ${REFRESH_ATTEMPTS} rate-limited attempts but keeps the session`, async () => {
    serve([limited('1'), limited('1'), limited('1')]);
    useAuthStore.setState({ status: 'signedIn', accessToken: 'stale', me: me as never });

    const result = refreshSession().catch((e: unknown) => e);
    await jest.advanceTimersByTimeAsync(2_000);

    expect(await result).toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(REFRESH_ATTEMPTS);
    expect(useAuthStore.getState().status).toBe('signedIn');
  });

  it('still ends the session at once on a 401', async () => {
    serve([json(401, { status: 401, code: 'REFRESH_INVALID' })]);
    useAuthStore.setState({ status: 'signedIn', accessToken: 'stale', me: me as never });

    await expect(refreshSession()).resolves.toBeNull();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useAuthStore.getState().status).toBe('signedOut');
  });
});

describe('signed out only when the server refuses the refresh token', () => {
  beforeEach(() => {
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue('refresh-1');
    useAuthStore.setState({ status: 'signedIn', accessToken: 'stale', me: me as never });
  });

  it.each([
    [
      'no network (the phone just woke up)',
      () => Promise.reject(new TypeError('Network request failed')),
    ],
    ['a 502 from the edge while the API restarts', () => json(502, { status: 502 })],
    ['a 503', () => json(503, { status: 503, code: 'UNAVAILABLE' })],
    ['a 500', () => json(500, { status: 500, code: 'INTERNAL' })],
  ])('%s keeps the session and its refresh token', async (_name, answer) => {
    serveRefresh(answer as () => Promise<Response>);
    const saved = jest.spyOn(refreshTokenStore, 'set');

    const error = await refreshSession().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const state = useAuthStore.getState();
    expect(state.status).toBe('signedIn');
    expect(state.me?.handle).toBe('fan');
    expect(saved).not.toHaveBeenCalled();
  });

  it.each(['REFRESH_INVALID', 'REFRESH_REUSED', 'REFRESH_EXPIRED', 'REFRESH_MISSING'])(
    'a 401 %s ends it',
    async (code) => {
      serveRefresh(() => json(401, { status: 401, code }));

      await expect(refreshSession()).resolves.toBeNull();

      expect(useAuthStore.getState().status).toBe('signedOut');
    },
  );

  it('a request whose refresh cannot reach the server fails with that error and leaves the user signed in', async () => {
    fetchMock.mockImplementation(async (url) =>
      String(url).endsWith('/auth/refresh')
        ? Promise.reject(new TypeError('Network request failed'))
        : json(401, { status: 401, code: 'UNAUTHENTICATED' }),
    );

    const error = await customFetch('/api/v1/me').catch((e: unknown) => e);

    expect(error).toMatchObject({ code: 'NETWORK_ERROR' });
    expect(useAuthStore.getState().status).toBe('signedIn');
  });
});

describe('one refresh at a time, with the newest refresh token', () => {
  it('bootstrap shares its refresh with a request that got a 401 at the same moment', async () => {
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue('refresh-1');
    let answer: () => void = () => {};
    const answered = new Promise<void>((resolve) => (answer = resolve));
    fetchMock.mockImplementation(async (url, init) => {
      if (String(url).endsWith('/auth/refresh')) {
        await answered;
        return json(200, session);
      }
      return new Headers(init?.headers).get('Authorization') === 'Bearer access-1'
        ? json(200, me)
        : json(401, { status: 401, code: 'UNAUTHENTICATED' });
    });

    const started = useAuthStore.getState().bootstrap();
    const request = customFetch('/api/v1/notifications');
    await new Promise((r) => setTimeout(r, 0));
    answer();
    await Promise.all([started, request]);

    // Two refreshes with one token would be reuse: the server revokes the family (RefreshRaceIT).
    expect(refreshCalls()).toEqual(['refresh-1']);
    expect(useAuthStore.getState().status).toBe('signedIn');
  });

  it('sends the newest refresh token even when the secure store failed to save it', async () => {
    // A store that keeps the first token whatever is written to it.
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue('refresh-1');
    jest.spyOn(refreshTokenStore, 'set').mockResolvedValue(undefined);
    let n = 0;
    serveRefresh(() => {
      n += 1;
      return json(200, {
        ...session,
        accessToken: `access-${n + 1}`,
        refreshToken: `refresh-${n + 1}`,
      });
    });

    await refreshOnce();
    await refreshOnce();

    // Sending refresh-1 twice would be reuse, and the end of the session.
    expect(refreshCalls()).toEqual(['refresh-1', 'refresh-2']);
  });

  it('a secure store that cannot be read in the middle of a session does not end it', async () => {
    fetchMock.mockImplementation(async (url) =>
      String(url).endsWith('/auth/login') ? json(200, session) : json(200, me),
    );
    await useAuthStore.getState().signIn('fan@example.com', 'correct horse battery');
    jest.spyOn(refreshTokenStore, 'get').mockRejectedValue(new Error('Keystore busy'));
    serveRefresh(() =>
      json(200, { ...session, accessToken: 'access-2', refreshToken: 'refresh-2' }),
    );

    await expect(refreshSession()).resolves.toBe('access-2');
    expect(refreshCalls()).toEqual(['refresh-1']);
  });
});

describe('starting the app without the server', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  async function bootstrap(): Promise<void> {
    const started = useAuthStore.getState().bootstrap();
    await jest.runAllTimersAsync();
    await started;
  }

  it(`tries ${BOOTSTRAP_ATTEMPTS} times, then says it cannot connect and keeps the refresh token; trying again signs in`, async () => {
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue('refresh-1');
    const saved = jest.spyOn(refreshTokenStore, 'set');
    serveRefresh(() => Promise.reject(new TypeError('Network request failed')));

    await bootstrap();

    expect(useAuthStore.getState().status).toBe('unreachable');
    expect(refreshCalls()).toHaveLength(BOOTSTRAP_ATTEMPTS);
    expect(saved).not.toHaveBeenCalledWith(null);

    serveRefresh(() => json(200, session));
    await bootstrap();

    expect(useAuthStore.getState().status).toBe('signedIn');
    expect(useAuthStore.getState().me?.handle).toBe('fan');
  });

  it('a secure store that cannot be read at start is not a sign-out', async () => {
    jest.spyOn(refreshTokenStore, 'get').mockRejectedValue(new Error('Keystore busy'));

    await bootstrap();

    expect(useAuthStore.getState().status).toBe('unreachable');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('nothing stored is a plain signed-out start, with no request', async () => {
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue(null);

    await bootstrap();

    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('a refused refresh token at start is a signed-out start', async () => {
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue('refresh-1');
    serveRefresh(() => json(401, { status: 401, code: 'REFRESH_EXPIRED' }));

    await bootstrap();

    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(refreshCalls()).toHaveLength(1);
  });
});
