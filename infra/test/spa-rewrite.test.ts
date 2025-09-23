import * as fs from 'node:fs';
import * as path from 'node:path';

interface CfRequest {
  uri: string;
}
type Handler = (event: { request: CfRequest }) => CfRequest;

/**
 * The CloudFront Function file is evaluated as-is (it has no exports, as CloudFront wants), so the test
 * runs exactly the code that is deployed.
 */
const source = fs.readFileSync(path.join(__dirname, '..', 'lib', 'functions', 'spa-rewrite.js'), 'utf8');
// eslint-disable-next-line @typescript-eslint/no-implied-eval
const handler = new Function(`${source}\nreturn handler;`)() as Handler;
const rewrite = (uri: string) => handler({ request: { uri } }).uri;

describe('spa-rewrite (CloudFront Function)', () => {
  it.each([
    ['/post/abc', '/index.html'],
    ['/', '/index.html'],
    ['/settings/profile', '/index.html'],
  ])('serves app route %s as %s', (uri, expected) => {
    expect(rewrite(uri)).toBe(expected);
  });

  it.each(['/assets/x.js', '/index.html', '/favicon.ico', '/_expo/static/js/web/entry-1a2b.js'])(
    'leaves file %s unchanged',
    (uri) => {
      expect(rewrite(uri)).toBe(uri);
    },
  );

  it.each(['/api/v1/x', '/api/v1/gigs/123', '/media/a/b.m3u8', '/media/a/b'])(
    'never rewrites API or media path %s',
    (uri) => {
      expect(rewrite(uri)).toBe(uri);
    },
  );
});
