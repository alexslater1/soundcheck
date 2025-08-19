import { consoleAccess, MIN_CONSOLE_WIDTH } from './access';

const web = { platform: 'web', width: 1440 } as const;

describe('consoleAccess', () => {
  it.each([
    [
      'the Android app, whoever it is',
      { ...web, platform: 'android', status: 'signedIn', role: 'ADMIN' },
      'native',
    ],
    ['signed out on web', { ...web, status: 'signedOut', role: undefined }, 'signedOut'],
    ['still resuming the session', { ...web, status: 'unknown', role: undefined }, 'loading'],
    ['signed in, /me not loaded', { ...web, status: 'signedIn', role: undefined }, 'loading'],
    ['a USER', { ...web, status: 'signedIn', role: 'USER' }, 'forbidden'],
    [
      'a USER on a narrow screen still gets "operators"',
      { ...web, width: 400, status: 'signedIn', role: 'USER' },
      'forbidden',
    ],
    [
      'an ADMIN on a phone browser',
      { ...web, width: 412, status: 'signedIn', role: 'ADMIN' },
      'narrow',
    ],
    [
      'an ADMIN just under the minimum',
      { ...web, width: MIN_CONSOLE_WIDTH - 1, status: 'signedIn', role: 'ADMIN' },
      'narrow',
    ],
    [
      'an ADMIN at the minimum',
      { ...web, width: MIN_CONSOLE_WIDTH, status: 'signedIn', role: 'ADMIN' },
      'shell',
    ],
    ['an ADMIN on a laptop', { ...web, status: 'signedIn', role: 'ADMIN' }, 'shell'],
  ] as const)('%s', (_name, input, expected) => {
    expect(consoleAccess(input)).toBe(expected);
  });
});
