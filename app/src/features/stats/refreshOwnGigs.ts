import type { QueryClient, QueryKey } from '@tanstack/react-query';

/**
 * Whether a cached query shows the user's own marked gigs or the stats counted from them: My gigs
 * (`/me/gigs`), their stats and the gigs behind each line (`/users/{id}/stats…`) and their Going list.
 */
export function showsOwnGigs(key: QueryKey, userId: string): boolean {
  const path = key[0];
  return (
    typeof path === 'string' &&
    (path === '/api/v1/me/gigs' ||
      path.startsWith(`/api/v1/users/${userId}/stats`) ||
      path === `/api/v1/users/${userId}/gigs/going`)
  );
}

/**
 * Asks again for everything {@link showsOwnGigs} matches. Marks change behind the Me tab's back: a post
 * going live or being deleted, a gig added in Create, a mark on a gig page.
 */
export function refreshOwnGigs(client: QueryClient, userId: string) {
  return client.invalidateQueries({ predicate: (query) => showsOwnGigs(query.queryKey, userId) });
}
