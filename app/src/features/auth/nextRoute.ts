import type { AuthStatus } from '@/features/auth/store';

/**
 * Where the root layout may send the user. `null` means "stay where you are". A console path (with its
 * query) is either the sign-in screen's `next` or where it returns afterwards.
 */
export type Redirect =
  | '/sign-in'
  | `/sign-in?next=${string}`
  | '/onboarding'
  | '/'
  | `/console${string}`
  | null;

export interface RouteState {
  status: AuthStatus;
  /** `me.onboarded`; `undefined` while `/me` has not loaded yet. */
  onboarded: boolean | undefined;
  /** First URL segment from expo-router (`'(auth)'`, `'onboarding'`, `'(tabs)'`, …). */
  group: string | undefined;
  /** The current path with its query (`/console/users?range=7d`); only console paths are remembered. */
  path?: string;
  /** The sign-in screen's `next` parameter: where to go once signed in. */
  next?: string;
  /** `me.role === 'ADMIN'`: only an admin is sent on to a console `next` after signing in. */
  admin?: boolean;
}

/**
 * Only the console is worth coming back to, and only a same-app path is followed: a `next` pointing
 * anywhere else (`//evil.example`, `https://…`) is ignored, so the parameter can't be an open redirect.
 */
export function safeNext(next: string | undefined): `/console${string}` | null {
  if (!next) return null;
  return /^\/console(?:[/?#]|$)/.test(next) && !next.includes('//')
    ? (next as `/console${string}`)
    : null;
}

/**
 * The redirect rules of the root layout as a pure function, so they can be tested as a table:
 * signed out → the auth screens; signed in but not onboarded → onboarding; onboarded users are moved
 * out of the auth and onboarding screens. Everything else stays put.
 */
export function nextRoute({ status, onboarded, group, path, next, admin }: RouteState): Redirect {
  // Still resuming, or could not reach the server to resume (the root shows "Can't connect" instead).
  if (status === 'unknown' || status === 'unreachable') return null;
  const inAuth = group === '(auth)';
  const inOnboarding = group === 'onboarding';
  if (status === 'signedOut') {
    if (inAuth) return null;
    // A signed-out visit to the console comes back to the same page after sign-in.
    const back = group === 'console' ? safeNext(path) : null;
    return back ? `/sign-in?next=${encodeURIComponent(back)}` : '/sign-in';
  }
  if (onboarded === undefined) return null;
  // Someone who signs out of the console and back in as a fan goes home, not to "for operators".
  const back = admin ? safeNext(next) : null;
  // The console needs no city or genres: the bootstrap admin is never onboarded, so the console
  // is exempt, and signing in for a console page goes straight there.
  if (!onboarded) {
    if (group === 'console') return null;
    if (inAuth && back) return back;
    return inOnboarding ? null : '/onboarding';
  }
  if (inAuth) return back ?? '/';
  return inOnboarding ? '/' : null;
}
