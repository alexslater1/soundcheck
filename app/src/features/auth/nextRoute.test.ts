import { nextRoute, type Redirect, type RouteState } from '@/features/auth/nextRoute';

type Row = [name: string, state: RouteState, expected: Redirect];

const rows: Row[] = [
  // Still bootstrapping: never redirect, whatever the screen.
  ['unknown on tabs', { status: 'unknown', onboarded: undefined, group: '(tabs)' }, null],
  ['unknown on auth', { status: 'unknown', onboarded: undefined, group: '(auth)' }, null],
  ['unknown at the root', { status: 'unknown', onboarded: undefined, group: undefined }, null],

  // Could not reach the server at start: not signed out, so never the sign-in screen.
  ['unreachable on tabs', { status: 'unreachable', onboarded: undefined, group: '(tabs)' }, null],
  [
    'unreachable at the root',
    { status: 'unreachable', onboarded: undefined, group: undefined },
    null,
  ],

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

  // The console round trip: signed out on a console page → sign-in → back to that page.
  [
    'signed out on a console page remembers it',
    {
      status: 'signedOut',
      onboarded: undefined,
      group: 'console',
      path: '/console/users?range=30d',
    },
    '/sign-in?next=%2Fconsole%2Fusers%3Frange%3D30d',
  ],
  [
    'signed out elsewhere remembers nothing',
    { status: 'signedOut', onboarded: undefined, group: '(tabs)', path: '/gig/1' },
    '/sign-in',
  ],
  [
    'signed in on auth with a console next goes there',
    { status: 'signedIn', onboarded: true, group: '(auth)', next: '/console/users?range=30d' },
    '/console/users?range=30d',
  ],
  [
    'a next outside the console is ignored',
    { status: 'signedIn', onboarded: true, group: '(auth)', next: '/settings' },
    '/',
  ],
  [
    'a next to another site is ignored',
    { status: 'signedIn', onboarded: true, group: '(auth)', next: '/console//evil.example' },
    '/',
  ],
  [
    'not onboarded (the bootstrap admin) signing in for the console goes there',
    { status: 'signedIn', onboarded: false, group: '(auth)', next: '/console' },
    '/console',
  ],
  [
    'not onboarded on the console stays',
    { status: 'signedIn', onboarded: false, group: 'console' },
    null,
  ],
  [
    'not onboarded with a next outside the console still onboards',
    { status: 'signedIn', onboarded: false, group: '(auth)', next: '/settings' },
    '/onboarding',
  ],
];

describe('nextRoute', () => {
  it.each(rows)('%s', (_name, state, expected) => {
    expect(nextRoute(state)).toBe(expected);
  });
});
