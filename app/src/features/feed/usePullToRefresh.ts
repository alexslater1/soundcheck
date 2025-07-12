import type { InfiniteData, QueryClient, QueryKey } from '@tanstack/react-query';
import { useCallback, useRef, useState } from 'react';

/** The refresh animation stays up at least this long, so a fast answer does not just flicker. */
export const MIN_REFRESH_MS = 600;

/**
 * Fetches a feed's first page again and drops the pages after it. The old posts stay on
 * screen until the new first page is in (no skeleton): a pull to refresh keeps showing the feed while
 * the header's bars dance. Asking again for page one with no cursor is what starts a new session of
 * the ranked feed, and gives the newest posts of the Following feed.
 */
export async function refreshFirstPage(client: QueryClient, queryKey: QueryKey): Promise<void> {
  client.setQueryData<InfiniteData<unknown, unknown>>(queryKey, (data) =>
    data ? { pages: data.pages.slice(0, 1), pageParams: data.pageParams.slice(0, 1) } : data,
  );
  await client.refetchQueries({ queryKey, exact: true });
}

/**
 * Runs `work` for a pull to refresh: `refreshing` is true until it is done (and for at least
 * {@link MIN_REFRESH_MS}); a second pull while one is running does nothing.
 */
export function usePullToRefresh(work: () => Promise<void>, minMs = MIN_REFRESH_MS) {
  const [refreshing, setRefreshing] = useState(false);
  const running = useRef(false);
  const refresh = useCallback(async () => {
    if (running.current) {
      return;
    }
    running.current = true;
    setRefreshing(true);
    try {
      await Promise.all([
        work().catch(() => undefined),
        new Promise<void>((resolve) => setTimeout(resolve, minMs)),
      ]);
    } finally {
      running.current = false;
      setRefreshing(false);
    }
  }, [work, minMs]);
  return { refreshing, refresh };
}
