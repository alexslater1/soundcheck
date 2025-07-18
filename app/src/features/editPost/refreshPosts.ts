import type { QueryClient } from '@tanstack/react-query';

/**
 * After a post changed (an edit, clips added or removed, a tag taken off): every cached read of posts is
 * read again — the post itself and the lists that show it (profile, gig, artist, venue, Me). Query keys
 * are the generated clients' URLs.
 */
export function refreshPosts(client: QueryClient): Promise<void> {
  return client.invalidateQueries({
    predicate: (query) => {
      const [key] = query.queryKey;
      return typeof key === 'string' && key.includes('/posts');
    },
  });
}
