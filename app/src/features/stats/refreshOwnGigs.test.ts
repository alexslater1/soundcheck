import { QueryClient } from '@tanstack/react-query';

import { refreshOwnGigs, showsOwnGigs } from './refreshOwnGigs';

describe('refreshOwnGigs', () => {
  it('matches the user’s own gigs and stats, not someone else’s and not other lists', () => {
    expect(showsOwnGigs(['/api/v1/me/gigs', { from: 'a' }], 'u1')).toBe(true);
    expect(showsOwnGigs(['/api/v1/users/u1/stats', { top: 10 }], 'u1')).toBe(true);
    expect(showsOwnGigs(['/api/v1/users/u1/stats/gigs', { artistId: 'a' }], 'u1')).toBe(true);
    expect(showsOwnGigs(['/api/v1/users/u1/stats/gigs/count'], 'u1')).toBe(true);
    expect(showsOwnGigs(['/api/v1/users/u1/gigs/going'], 'u1')).toBe(true);
    expect(showsOwnGigs(['/api/v1/users/u2/stats'], 'u1')).toBe(false);
    expect(showsOwnGigs(['/api/v1/gigs'], 'u1')).toBe(false);
    expect(showsOwnGigs(['/api/v1/users/u1/posts'], 'u1')).toBe(false);
  });

  it('marks exactly those queries stale', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } });
    client.setQueryData(['/api/v1/users/u1/stats', { top: 10 }], {});
    client.setQueryData(['/api/v1/users/u2/stats', { top: 10 }], {});
    await refreshOwnGigs(client, 'u1');
    expect(client.getQueryState(['/api/v1/users/u1/stats', { top: 10 }])?.isInvalidated).toBe(true);
    expect(client.getQueryState(['/api/v1/users/u2/stats', { top: 10 }])?.isInvalidated).toBe(
      false,
    );
  });
});
