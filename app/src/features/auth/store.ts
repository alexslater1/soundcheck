import { create } from 'zustand';

import { login, logout, refresh, register } from '@/api/generated/auth/auth';
import { getMe } from '@/api/generated/me/me';
import type { AuthResponse, MeResponse, RefreshRequest } from '@/api/generated/model';
import {
  ApiError,
  clientKind,
  type FetchOptions,
  refreshOnce,
  setAccessTokenProvider,
  setRefreshHandler,
} from '@/api/http';
import { queryClient } from '@/api/queryClient';
import { telemetry } from '@/features/telemetry/telemetry';
import { refreshTokenStore } from '@/lib/secureStore';

/**
 * Client auth state: the access token lives in memory only; the refresh token in the
 * secure store (Android) or the HttpOnly cookie (web). `bootstrap()` runs once at app start and tries
 * to resume the session; `http.ts` calls `refreshSession` single-flight on 401.
 *
 * The rule: the session ends only when the server answers and refuses the refresh token.
 * No network, a 5xx or a rate limit keeps it, and the next request tries again; at start the app says
 * it cannot connect (`unreachable`) instead of showing the sign-in screen.
 */
export type AuthStatus = 'unknown' | 'unreachable' | 'signedOut' | 'signedIn';

type AuthState = {
  status: AuthStatus;
  accessToken: string | null;
  /** When the access token stops working, by this device's clock (ms since the epoch). */
  accessTokenExpiresAt: number | null;
  me: MeResponse | null;
  bootstrap: () => Promise<void>;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (input: {
    email: string;
    password: string;
    handle: string;
    displayName: string;
  }) => Promise<void>;
  signOut: () => Promise<void>;
  reloadMe: () => Promise<void>;
  /** Called after a successful session response from any auth endpoint. */
  acceptSession: (session: AuthResponse) => Promise<void>;
  clear: () => void;
};

function refreshBody(token: string | null): { refreshToken?: string } | undefined {
  return clientKind() === 'android' && token ? { refreshToken: token } : undefined;
}

/**
 * `logout` and `refresh` as they run: the refresh body is optional (`undefined` sends no body: the web
 * has its cookie) and the options go to customFetch, but orval 7 types the body as required and the
 * options as plain RequestInit.
 */
type SessionCall<T> = (body: RefreshRequest | undefined, options?: FetchOptions) => Promise<T>;
const logoutSession = logout as SessionCall<void>;
const refreshSessionToken = refresh as SessionCall<AuthResponse>;

/**
 * The newest refresh token this run has been given (Android; web keeps it in its cookie). The secure
 * store holds it for the next launch, but this copy is the one sent: a secure-store write that failed,
 * or a read that fails later, must never make the app present a token the server has already rotated,
 * because that is reuse to the server and ends the session.
 */
let latestRefreshToken: string | null = null;

async function keepRefreshToken(token: string | null): Promise<void> {
  latestRefreshToken = token;
  await refreshTokenStore.set(token);
}

/** The refresh token to present; rejects when only the secure store has it and it cannot be read. */
async function currentRefreshToken(): Promise<string | null> {
  if (latestRefreshToken === null) {
    latestRefreshToken = await refreshTokenStore.get();
  }
  return latestRefreshToken;
}

function expiresAt(expiresInSeconds: number): number {
  return Date.now() + expiresInSeconds * 1000;
}

/** How many times the app tries to resume the session at start before it says it cannot connect. */
export const BOOTSTRAP_ATTEMPTS = 3;
const BOOTSTRAP_RETRY_MS = 1_000;

export const useAuthStore = create<AuthState>((set, get) => ({
  status: 'unknown',
  accessToken: null,
  accessTokenExpiresAt: null,
  me: null,

  async acceptSession(session) {
    set({
      accessToken: session.accessToken,
      accessTokenExpiresAt: expiresAt(session.expiresIn),
      status: 'signedIn',
    });
    await keepRefreshToken(session.refreshToken ?? null);
    await get().reloadMe();
  },

  clear() {
    latestRefreshToken = null;
    set({ status: 'signedOut', accessToken: null, accessTokenExpiresAt: null, me: null });
    void refreshTokenStore.set(null);
    // Everything cached was fetched as this user (their attendance, follows, distances): drop it so the
    // next person to sign in on this device starts from nothing.
    queryClient.clear();
    // Events that were waiting belong to the account that has just left.
    telemetry.clear();
  },

  async bootstrap() {
    set({ status: 'unknown' });
    for (let attempt = 1; ; attempt++) {
      try {
        // Through the single flight: a screen's 401 at the same moment must not start a second refresh.
        const token = await refreshOnce();
        if (!token) {
          get().clear();
          return;
        }
        await get().reloadMe();
        set({ status: 'signedIn' });
        return;
      } catch {
        // The server refused the session while /me was loading: that is an answer, not an outage.
        if (get().status === 'signedOut') return;
      }
      // No network, a 5xx, an unreadable secure store: try again shortly, then say so. The refresh
      // token is kept, so "Try again" (or the next launch) resumes the session.
      if (attempt >= BOOTSTRAP_ATTEMPTS) {
        set({ status: 'unreachable' });
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, BOOTSTRAP_RETRY_MS * attempt));
    }
  },

  async signIn(email, password) {
    const options: FetchOptions = { skipRefresh: true };
    const session = await login({ email, password }, options);
    await get().acceptSession(session);
  },

  async signUp(input) {
    const options: FetchOptions = { skipRefresh: true };
    const session = await register(input, options);
    await get().acceptSession(session);
  },

  async signOut() {
    try {
      const token = await currentRefreshToken();
      await logoutSession(refreshBody(token), { skipRefresh: true, headers: requestedWith() });
    } catch {
      // Already signed out server-side, offline, or the token unreadable: local state is cleared
      // regardless (an unrevoked family expires on its own).
    }
    get().clear();
  },

  async reloadMe() {
    const me = await getMe();
    set({ me });
  },
}));

function requestedWith(): Record<string, string> {
  return clientKind() === 'web' ? { 'X-Requested-With': 'soundcheck' } : {};
}

/**
 * A rate-limited refresh (429: `/auth/refresh` allows a few a minute per address) is not a lost
 * session: wait what the server's `Retry-After` asks, between 1 and this many seconds, and try
 * again, up to {@link REFRESH_ATTEMPTS} attempts in all.
 */
export const REFRESH_MAX_WAIT_S = 30;
export const REFRESH_ATTEMPTS = 3;
const REFRESH_DEFAULT_WAIT_S = 2;

function waitBeforeRetry(error: ApiError): Promise<void> {
  const seconds = Math.min(
    REFRESH_MAX_WAIT_S,
    Math.max(1, error.retryAfterSeconds ?? REFRESH_DEFAULT_WAIT_S),
  );
  return new Promise((resolve) => setTimeout(resolve, seconds * 1000));
}

/**
 * Whether a failed refresh is the server refusing the refresh token (invalid, reused, expired, missing):
 * a 4xx, apart from a timeout (408) and a rate limit (429), which say "not now" rather than "no".
 * Anything else (no network, a 5xx such as the edge's 502 while the API restarts) is not an answer.
 */
export function refreshRefused(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status >= 400 &&
    error.status < 500 &&
    error.status !== 408 &&
    error.status !== 429
  );
}

/**
 * One refresh round trip; returns the new access token, or null (and clears state) when the server
 * refused the refresh token or there is none. A 429 is waited out and retried. When the server
 * cannot be reached or does not answer, it **rejects** with that error and keeps the session and its
 * refresh token. Call it through `refreshOnce()`, never directly: two at once would be reuse.
 */
export async function refreshSession(): Promise<string | null> {
  const token = clientKind() === 'android' ? await currentRefreshToken() : null;
  if (clientKind() === 'android' && !token) {
    useAuthStore.getState().clear();
    return null;
  }
  for (let attempt = 1; ; attempt++) {
    try {
      const session = await refreshSessionToken(refreshBody(token), {
        skipRefresh: true,
        headers: requestedWith(),
      });
      useAuthStore.setState({
        accessToken: session.accessToken,
        accessTokenExpiresAt: expiresAt(session.expiresIn),
        status: 'signedIn',
      });
      await keepRefreshToken(session.refreshToken ?? null);
      return session.accessToken;
    } catch (error) {
      if (error instanceof ApiError && error.status === 429 && attempt < REFRESH_ATTEMPTS) {
        await waitBeforeRetry(error);
        continue;
      }
      if (refreshRefused(error)) {
        useAuthStore.getState().clear();
        return null;
      }
      throw error;
    }
  }
}

// Wire the HTTP layer once: token provider + single-flight refresh handler.
setAccessTokenProvider(() => {
  const { accessToken, accessTokenExpiresAt } = useAuthStore.getState();
  if (!accessToken) return null;
  return accessTokenExpiresAt === null
    ? { token: accessToken }
    : { token: accessToken, expiresAt: accessTokenExpiresAt };
});
setRefreshHandler(refreshSession);
