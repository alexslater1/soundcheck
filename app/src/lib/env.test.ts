import { parseEnv, type Runtime } from './env';

const web: Runtime = { os: 'web', origin: 'https://d1abcdef.cloudfront.net' };
const android: Runtime = { os: 'android', origin: undefined };

describe('parseEnv', () => {
  it('defaults to the local edge', () => {
    expect(parseEnv({}, android).apiBaseUrl).toBe('http://localhost:8088');
    expect(parseEnv({}, web).apiBaseUrl).toBe('http://localhost:8088');
  });

  it('reads EXPO_PUBLIC_API_BASE_URL and strips trailing slashes', () => {
    expect(
      parseEnv({ EXPO_PUBLIC_API_BASE_URL: 'https://d123.cloudfront.net/' }, android).apiBaseUrl,
    ).toBe('https://d123.cloudfront.net');
  });

  it('rejects a non-URL', () => {
    expect(() => parseEnv({ EXPO_PUBLIC_API_BASE_URL: 'not a url' }, android)).toThrow();
  });

  describe('same-origin web build', () => {
    it('uses the origin that served the page when the value is empty on the web', () => {
      expect(parseEnv({ EXPO_PUBLIC_API_BASE_URL: '' }, web).apiBaseUrl).toBe(
        'https://d1abcdef.cloudfront.net',
      );
    });

    it('keeps an absolute URL on the web', () => {
      expect(parseEnv({ EXPO_PUBLIC_API_BASE_URL: 'http://localhost:8088' }, web).apiBaseUrl).toBe(
        'http://localhost:8088',
      );
    });

    it('refuses an empty value on native, which has no origin', () => {
      expect(() => parseEnv({ EXPO_PUBLIC_API_BASE_URL: '' }, android)).toThrow(
        /native build needs an absolute URL/,
      );
    });

    it('refuses an empty value on a web runtime without an origin', () => {
      expect(() =>
        parseEnv({ EXPO_PUBLIC_API_BASE_URL: '' }, { os: 'web', origin: undefined }),
      ).toThrow();
    });
  });
});
