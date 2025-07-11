import { useInfiniteQuery } from '@tanstack/react-query';

import { getListGigsQueryKey, listGigs } from '@/api/generated/catalog/catalog';
import type { ListGigsParams } from '@/api/generated/model';

/**
 * `GET /gigs` as an infinite list: each page's `nextCursor` is the `cursor` of the next request, and a
 * `null` cursor ends the list. The key starts with the generated list key, so invalidating
 * `/api/v1/gigs` (after an attendance change, say) refreshes this too. `enabled` false holds it back
 * (until the first location check is over).
 */
export function useGigList(params: Omit<ListGigsParams, 'cursor'>, enabled = true) {
  return useInfiniteQuery({
    enabled,
    queryKey: [...getListGigsQueryKey(params), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      listGigs({ ...params, ...(pageParam ? { cursor: pageParam } : {}) }, { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  });
}
