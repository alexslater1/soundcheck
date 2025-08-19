import type { AuthStatus } from '@/features/auth/store';

/**
 * What the console's layout shows. The server is the authority on every admin request (two
 * locks); this only shapes the UI, and makes sure a non-admin's browser sends no admin
 * request at all.
 */
export type ConsoleAccess =
  /** The Android app: the console is web only. */
  | 'native'
  /** The session is still being resumed. */
  | 'loading'
  /** Signed out: the root layout sends the user to sign-in and back here afterwards. */
  | 'signedOut'
  /** Signed in without the ADMIN role. */
  | 'forbidden'
  /** An admin on a screen too narrow for the tables (a phone's browser). */
  | 'narrow'
  | 'shell';

/** Below this width the console says "open it on a wider screen" instead of squashing the layout. */
export const MIN_CONSOLE_WIDTH = 1024;

export function consoleAccess(input: {
  platform: string;
  status: AuthStatus;
  role: string | undefined;
  width: number;
}): ConsoleAccess {
  if (input.platform !== 'web') return 'native';
  if (input.status === 'signedOut') return 'signedOut';
  if (input.status !== 'signedIn' || input.role === undefined) return 'loading';
  if (input.role !== 'ADMIN') return 'forbidden';
  if (input.width < MIN_CONSOLE_WIDTH) return 'narrow';
  return 'shell';
}
