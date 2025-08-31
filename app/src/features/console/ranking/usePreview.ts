import { useQuery, type QueryKey } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { adminPreviewRanking } from '@/api/generated/admin-ranking/admin-ranking';
import type { RankingPreviewRequest } from '@/api/generated/model';

/** How long the playground waits after the last slider move before it asks for a preview. */
export const PREVIEW_DEBOUNCE_MS = 300;

/** The value as it was `ms` after it last changed. */
export function useDebouncedValue<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return settled;
}

export function previewQueryKey(request: RankingPreviewRequest) {
  return ['/api/v1/admin/ranking/preview', request] as const;
}

/**
 * The preview for a request, debounced. The request is the query key, so an answer always
 * belongs to the request it was asked for: when a slider moves again, the older request is aborted
 * (React Query passes its `signal` to the fetch) and its answer, should it still arrive, is filed under
 * its own key and never shown as the current one. While a new answer is on its way the last one stays
 * on screen, marked `updating`, so the columns don't flash empty on every move.
 */
export function usePreview(request: RankingPreviewRequest | null) {
  // Debounced by content, not identity: a re-render that rebuilds an equal request doesn't restart it.
  const key = JSON.stringify(request);
  const settledKey = useDebouncedValue(key, PREVIEW_DEBOUNCE_MS);
  const debounced = useMemo(
    () => JSON.parse(settledKey) as RankingPreviewRequest | null,
    [settledKey],
  );
  const query = useQuery({
    queryKey: debounced ? previewQueryKey(debounced) : ['/api/v1/admin/ranking/preview', 'none'],
    queryFn: ({ signal }) => adminPreviewRanking(debounced as RankingPreviewRequest, { signal }),
    enabled: debounced !== null,
    // The last answer stays up while the next loads, but never another user's.
    placeholderData: (previous, previousQuery) =>
      sameUser(previousQuery?.queryKey, debounced) ? previous : undefined,
    staleTime: 30_000,
  });
  const settling = key !== settledKey;
  return {
    data: query.data,
    status: query.status,
    error: query.error,
    /** True while what is shown is not yet the answer to the latest settings. */
    updating: settling || query.isPlaceholderData || query.isFetching,
    refetch: query.refetch,
  };
}

function sameUser(key: QueryKey | undefined, request: RankingPreviewRequest | null): boolean {
  const previous = key?.[1];
  return (
    request !== null &&
    typeof previous === 'object' &&
    previous !== null &&
    (previous as RankingPreviewRequest).userId === request.userId
  );
}
