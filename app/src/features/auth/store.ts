import { create } from 'zustand';

import { login, logout, refresh, register } from '@/api/generated/auth/auth';
import { getMe } from '@/api/generated/me/me';
import type { AuthResponse, MeResponse, RefreshRequest } from '@/api/generated/model';
import {
  clientKind,
  type FetchOptions,
  setAccessTokenProvider,
  setRefreshHandler,
} from '@/api/http';
import { refreshTokenStore } from '@/lib/secureStore';

/**
 * Client auth state: the access token lives in memory only; the refresh token in the
 * secure store (Android) or the HttpOnly cookie (web). `bootstrap()` runs once at app start and tries
 * to resume the session; `http.ts` calls `refreshSession` single-flight on 401.
 */
export type AuthStatus = 'unknown' | 'signedOut' | 'signedIn';

type AuthState = {
  status: AuthStatus;
  accessToken: string | null;
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

export const useAuthStore = create<AuthState>((set, get) => ({
  status: 'unknown',
  accessToken: null,
  me: null,

  async acceptSession(session) {
    set({ accessToken: session.accessToken, status: 'signedIn' });
    await refreshTokenStore.set(session.refreshToken ?? null);
    await get().reloadMe();
  },

  clear() {
    set({ status: 'signedOut', accessToken: null, me: null });
    void refreshTokenStore.set(null);
  },

  async bootstrap() {
    try {
      const token = await refreshSession();
      if (token) {
        await get().reloadMe();
        set({ status: 'signedIn' });
        return;
      }
    } catch {
      // Network down while resuming: fall through and start signed out rather than hang on the spinner.
    }
    get().clear();
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
    const stored = await refreshTokenStore.get();
    try {
      await logoutSession(refreshBody(stored), { skipRefresh: true, headers: requestedWith() });
    } catch {
      // Already signed out server-side or offline: local state is cleared regardless.
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

/** One refresh round trip; returns the new access token or null (and clears state) when it fails. */
export async function refreshSession(): Promise<string | null> {
  const stored = await refreshTokenStore.get();
  if (clientKind() === 'android' && !stored) {
    return null;
  }
  try {
    const session = await refreshSessionToken(refreshBody(stored), {
      skipRefresh: true,
      headers: requestedWith(),
    });
    useAuthStore.setState({ accessToken: session.accessToken, status: 'signedIn' });
    await refreshTokenStore.set(session.refreshToken ?? null);
    return session.accessToken;
  } catch {
    useAuthStore.getState().clear();
    return null;
  }
}

// Wire the HTTP layer once: token provider + single-flight refresh handler.
setAccessTokenProvider(() => useAuthStore.getState().accessToken);
setRefreshHandler(refreshSession);
