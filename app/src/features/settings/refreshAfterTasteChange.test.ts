import { QueryClient } from '@tanstack/react-query';

import { dependsOnHome, refreshAfterTasteChange } from './refreshAfterTasteChange';

describe('refreshAfterTasteChange', () => {
  it('asks again for the gig lists, starts For You afresh, and leaves the rest alone', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } });
    const gigs = ['/api/v1/gigs', { limit: 20 }, 'infinite'];
    const days = ['/api/v1/gigs/days', { from: 'a' }];
    const forYou = ['/api/v1/feed/home', 'infinite'];
    const following = ['/api/v1/feed/following', 'infinite'];
    const post = ['/api/v1/posts/p1'];
    for (const key of [gigs, days, forYou, following, post]) {
      client.setQueryData(key, { cached: true });
    }

    await refreshAfterTasteChange(client);

    expect(client.getQueryState(gigs)?.isInvalidated).toBe(true);
    expect(client.getQueryState(days)?.isInvalidated).toBe(true);
    // Reset: its cached pages are gone, so the next session is ranked with the new taste.
    expect(client.getQueryData(forYou)).toBeUndefined();
    expect(client.getQueryData(following)).toEqual({ cached: true });
    expect(client.getQueryState(following)?.isInvalidated).toBe(false);
    expect(client.getQueryState(post)?.isInvalidated).toBe(false);
  });

  it('matches only the lists that read the home city', () => {
    expect(dependsOnHome(['/api/v1/gigs'])).toBe(true);
    expect(dependsOnHome(['/api/v1/gigs/days', {}])).toBe(true);
    expect(dependsOnHome(['/api/v1/me/gigs'])).toBe(false);
    expect(dependsOnHome(['/api/v1/feed/home'])).toBe(false);
  });
});
