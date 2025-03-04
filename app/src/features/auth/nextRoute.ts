import type { AuthStatus } from '@/features/auth/store';

/** Where the root layout may send the user. `null` means "stay where you are". */
export type Redirect = '/sign-in' | '/onboarding' | '/' | null;

export interface RouteState {
  status: AuthStatus;
  /** `me.onboarded`; `undefined` while `/me` has not loaded yet. */
  onboarded: boolean | undefined;
  /** First URL segment from expo-router (`'(auth)'`, `'onboarding'`, `'(tabs)'`, …). */
  group: string | undefined;
}

/**
 * The redirect rules of the root layout as a pure function, so they can be tested as a table:
 * signed out → the auth screens; signed in but not onboarded → onboarding; onboarded users are moved
 * out of the auth and onboarding screens. Everything else stays put.
 */
export function nextRoute({ status, onboarded, group }: RouteState): Redirect {
  if (status === 'unknown') return null;
  const inAuth = group === '(auth)';
  const inOnboarding = group === 'onboarding';
  if (status === 'signedOut') return inAuth ? null : '/sign-in';
  if (onboarded === undefined) return null;
  if (!onboarded) return inOnboarding ? null : '/onboarding';
  return inAuth || inOnboarding ? '/' : null;
}
