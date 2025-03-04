import { nextRoute, type Redirect, type RouteState } from '@/features/auth/nextRoute';

type Row = [name: string, state: RouteState, expected: Redirect];

const rows: Row[] = [
  // Still bootstrapping: never redirect, whatever the screen.
  ['unknown on tabs', { status: 'unknown', onboarded: undefined, group: '(tabs)' }, null],
  ['unknown on auth', { status: 'unknown', onboarded: undefined, group: '(auth)' }, null],
  ['unknown at the root', { status: 'unknown', onboarded: undefined, group: undefined }, null],

  // Signed out: everything except the auth screens goes to sign-in.
  [
    'signed out on tabs',
    { status: 'signedOut', onboarded: undefined, group: '(tabs)' },
    '/sign-in',
  ],
  [
    'signed out at the root',
    { status: 'signedOut', onboarded: undefined, group: undefined },
    '/sign-in',
  ],
  [
    'signed out on onboarding',
    { status: 'signedOut', onboarded: undefined, group: 'onboarding' },
    '/sign-in',
  ],
  [
    'signed out on settings',
    { status: 'signedOut', onboarded: undefined, group: 'settings' },
    '/sign-in',
  ],
  [
    'signed out on console',
    { status: 'signedOut', onboarded: undefined, group: 'console' },
    '/sign-in',
  ],
  ['signed out on auth', { status: 'signedOut', onboarded: undefined, group: '(auth)' }, null],

  // Signed in, /me not loaded yet: wait.
  [
    'signed in, me loading, on auth',
    { status: 'signedIn', onboarded: undefined, group: '(auth)' },
    null,
  ],
  [
    'signed in, me loading, on tabs',
    { status: 'signedIn', onboarded: undefined, group: '(tabs)' },
    null,
  ],

  // Signed in, not onboarded: everything except onboarding goes to onboarding.
  [
    'not onboarded on auth',
    { status: 'signedIn', onboarded: false, group: '(auth)' },
    '/onboarding',
  ],
  [
    'not onboarded on tabs',
    { status: 'signedIn', onboarded: false, group: '(tabs)' },
    '/onboarding',
  ],
  [
    'not onboarded at the root',
    { status: 'signedIn', onboarded: false, group: undefined },
    '/onboarding',
  ],
  [
    'not onboarded on settings',
    { status: 'signedIn', onboarded: false, group: 'settings' },
    '/onboarding',
  ],
  [
    'not onboarded on onboarding',
    { status: 'signedIn', onboarded: false, group: 'onboarding' },
    null,
  ],

  // Onboarded: leave the auth and onboarding screens, stay anywhere else.
  ['onboarded on auth', { status: 'signedIn', onboarded: true, group: '(auth)' }, '/'],
  ['onboarded on onboarding', { status: 'signedIn', onboarded: true, group: 'onboarding' }, '/'],
  ['onboarded on tabs', { status: 'signedIn', onboarded: true, group: '(tabs)' }, null],
  ['onboarded at the root', { status: 'signedIn', onboarded: true, group: undefined }, null],
  ['onboarded on settings', { status: 'signedIn', onboarded: true, group: 'settings' }, null],
  ['onboarded on console', { status: 'signedIn', onboarded: true, group: 'console' }, null],
];

describe('nextRoute', () => {
  it.each(rows)('%s', (_name, state, expected) => {
    expect(nextRoute(state)).toBe(expected);
  });
});
