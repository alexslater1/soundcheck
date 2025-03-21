import { queryClient } from '@/api/queryClient';

import { refreshSession, useAuthStore } from './store';

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
  useAuthStore.setState({ status: 'unknown', accessToken: null, me: null });
});

describe('auth store', () => {
  it('bootstrap ends signed out (not stuck) when /me fails after a refresh', async () => {
    const { refreshTokenStore } =
      jest.requireActual<typeof import('@/lib/secureStore')>('@/lib/secureStore');
    jest.spyOn(refreshTokenStore, 'get').mockResolvedValue('refresh-1');
    fetchMock.mockImplementation(async (url) =>
      String(url).endsWith('/auth/refresh')
        ? json(200, session)
        : Promise.reject(new TypeError('offline')),
    );

    await useAuthStore.getState().bootstrap();

    expect(useAuthStore.getState().status).toBe('signedOut');
  });

  it('signIn stores the access token in memory and loads /me', async () => {
    fetchMock.mockImplementation(async (url) =>
      String(url).endsWith('/auth/login') ? json(200, session) : json(200, me),
    );

    await useAuthStore.getState().signIn('fan@example.com', 'correct horse battery');

    const state = useAuthStore.getState();
    expect(state.status).toBe('signedIn');
    expect(state.accessToken).toBe('access-1');
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

  it('refreshSession clears the session when the refresh token is gone', async () => {
    useAuthStore.setState({ status: 'signedIn', accessToken: 'stale', me: me as never });
    // Android with no stored refresh token: no request is even attempted.
    expect(await refreshSession()).toBeNull();
    expect(useAuthStore.getState().status).toBe('signedIn');
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
