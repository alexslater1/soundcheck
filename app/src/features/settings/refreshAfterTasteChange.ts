import type { QueryClient, QueryKey } from '@tanstack/react-query';

import { getGetHomeFeedQueryKey } from '@/api/generated/feed/feed';

/** Lists whose answer depends on the home city when no place is given: gigs, their calendar days. */
export const dependsOnHome = (key: QueryKey) =>
  typeof key[0] === 'string' && key[0].startsWith('/api/v1/gigs');

const isForYou = (key: QueryKey) => key[0] === getGetHomeFeedQueryKey()[0];

/**
 * A new home city or new genres take effect at once, without a restart. The server reads both on
 * every request, so the app only has to ask again: the gig lists (the Gigs tab's "Near …", Create's
 * gigs nearby) and the For You feed, whose next session is ranked with the new taste. For You is reset
 * rather than refetched: its pages belong to one server session (`ForYouFeed`).
 */
export async function refreshAfterTasteChange(client: QueryClient) {
  await Promise.all([
    client.invalidateQueries({ predicate: (query) => dependsOnHome(query.queryKey) }),
    client.resetQueries({ predicate: (query) => isForYou(query.queryKey) }),
  ]);
}
