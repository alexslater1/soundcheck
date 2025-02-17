import { parseEnv } from './env';

describe('parseEnv', () => {
  it('defaults to the local edge', () => {
    expect(parseEnv({}).apiBaseUrl).toBe('http://localhost:8088');
  });

  it('reads EXPO_PUBLIC_API_BASE_URL and strips trailing slashes', () => {
    expect(parseEnv({ EXPO_PUBLIC_API_BASE_URL: 'https://d123.cloudfront.net/' }).apiBaseUrl).toBe(
      'https://d123.cloudfront.net',
    );
  });

  it('rejects a non-URL', () => {
    expect(() => parseEnv({ EXPO_PUBLIC_API_BASE_URL: 'not a url' })).toThrow();
  });
});
