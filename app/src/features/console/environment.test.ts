import { environmentLabel } from './environment';

describe('environmentLabel', () => {
  it.each([
    ['http://localhost:8088', 'LOCAL'],
    ['http://127.0.0.1:8080', 'LOCAL'],
    ['http://192.168.1.20:8088', 'LOCAL'],
    ['https://d123.cloudfront.net', 'AWS'],
    ['not a url', 'LOCAL'],
  ])('%s → %s', (url, label) => {
    expect(environmentLabel(url)).toBe(label);
  });
});
