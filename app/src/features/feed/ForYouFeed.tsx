import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo } from 'react';
import { View } from 'react-native';

import { getGetHomeFeedQueryKey, getHomeFeed } from '@/api/generated/feed/feed';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { PostPager, type PagerItem } from '@/features/posts/PostPager';
import { telemetry } from '@/features/telemetry/telemetry';

import { isSessionExpired } from './sessionExpired';
import { refreshFirstPage, usePullToRefresh } from './usePullToRefresh';

const KEY = [...getGetHomeFeedQueryKey(), 'infinite'] as const;

/**
 * The ranked For You feed. The server builds a session on the first request and the
 * pages that follow are cut from it, so the query must not refetch by itself: a refetch of page one
 * would be a different session. It is fetched once, extended as the user scrolls, and replaced only
 * when the user refreshes or the server says the session has expired (410), in which case it starts
 * again from the first page without the user having to do anything. A pull to refresh keeps the old
 * posts on screen until the new session's first page is in; pressing the tab again starts over.
 * `visible` is false while Following shows in its place.
 */
export function ForYouFeed({
  refreshToken = 0,
  visible = true,
}: {
  refreshToken?: number;
  visible?: boolean;
}) {
  const client = useQueryClient();
  const feed = useInfiniteQuery({
    queryKey: KEY,
    queryFn: ({ pageParam }) => getHomeFeed({ limit: 10, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
    // An expired session cannot be retried into existence.
    retry: (failures, error) => !isSessionExpired(error) && failures < 1,
  });

  // What was just seen and liked must reach the server before it ranks again; otherwise the new
  // session would not know and would lead with the same clips.
  const restart = useCallback(async () => {
    await telemetry.flush();
    await client.resetQueries({ queryKey: KEY });
  }, [client]);

  const pull = usePullToRefresh(
    useCallback(async () => {
      await telemetry.flush();
      await refreshFirstPage(client, KEY);
    }, [client]),
  );

  const expired = isSessionExpired(feed.error);
  useEffect(() => {
    if (expired) {
      void restart();
    }
  }, [expired, restart]);
  // The Home screen asks for a new session when "For You" is pressed again.
  useEffect(() => {
    if (refreshToken > 0) {
      void restart();
    }
  }, [refreshToken, restart]);

  const hasNextPage = feed.hasNextPage;
  const fetchingNext = feed.isFetchingNextPage;
  const fetchNextPage = feed.fetchNextPage;
  // A page whose posts have all gone since the session was built comes back empty: keep going.
  const lastPageEmpty = feed.data ? feed.data.pages.at(-1)?.items.length === 0 : false;
  useEffect(() => {
    if (lastPageEmpty && hasNextPage && !fetchingNext) {
      void fetchNextPage();
    }
  }, [lastPageEmpty, hasNextPage, fetchingNext, fetchNextPage]);

  const items: PagerItem[] = useMemo(
    () =>
      (feed.data?.pages ?? []).flatMap((page) =>
        page.items.map((item) => ({
          post: item.post,
          position: item.position,
          reasons: item.reasons,
          sessionId: page.sessionId,
        })),
      ),
    [feed.data],
  );

  if (feed.isPending || expired || (items.length === 0 && hasNextPage)) {
    return (
      <View testID="for-you-loading">
        <Skeleton height={420} />
      </View>
    );
  }
  if (!feed.data) {
    return (
      <StateMessage
        testID="for-you-error"
        title="Could not load your feed"
        onRetry={() => void restart()}
      />
    );
  }
  if (items.length === 0) {
    return (
      <StateMessage
        testID="for-you-empty"
        title="Nothing to show yet"
        detail="Clips appear here as people post them. Follow artists and mark gigs to make it yours."
        onRetry={() => void restart()}
      />
    );
  }
  return (
    <PostPager
      items={items}
      surface="FOR_YOU"
      onEndReached={() => {
        if (hasNextPage && !fetchingNext) {
          void fetchNextPage();
        }
      }}
      onGone={(postId) =>
        // Hidden or deleted: drop it from the pages already loaded; the session itself stays.
        client.setQueryData<typeof feed.data>(KEY, (data) =>
          data
            ? {
                ...data,
                pages: data.pages.map((page) => ({
                  ...page,
                  items: page.items.filter((item) => item.post.id !== postId),
                })),
              }
            : data,
        )
      }
      onRefresh={() => void pull.refresh()}
      refreshing={pull.refreshing}
      visible={visible}
    />
  );
}
