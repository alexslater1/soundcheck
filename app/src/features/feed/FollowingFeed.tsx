import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef } from 'react';
import { View } from 'react-native';

import { getFollowingFeed, getGetFollowingFeedQueryKey } from '@/api/generated/feed/feed';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { PostPager } from '@/features/posts/PostPager';

import { refreshFirstPage, usePullToRefresh } from './usePullToRefresh';

const KEY = [...getGetFollowingFeedQueryKey(), 'infinite'] as const;

/**
 * Posts by the people you follow, newest post first (a clip of last week's gig posted today is new
 * to followers, and every card shows the gig's date); pages follow the server's cursor. Nothing
 * refetches it behind the viewer's back, because new posts arriving at the top would move the list
 * under them: pull it down to refresh, or press the tab again, which drops the loaded pages and
 * starts from the top (`refreshToken`).
 */
export function FollowingFeed({
  refreshToken = 0,
  visible = true,
}: {
  refreshToken?: number;
  visible?: boolean;
}) {
  const client = useQueryClient();
  const feed = useInfiniteQuery({
    queryKey: KEY,
    queryFn: ({ pageParam }) => getFollowingFeed({ limit: 10, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const pull = usePullToRefresh(useCallback(() => refreshFirstPage(client, KEY), [client]));

  // Home stays mounted under the screens opened from it. An empty feed has nothing to lose, so coming
  // back to it (after following someone) looks again; a feed with posts waits for a pull.
  const refetch = feed.refetch;
  const empty = feed.data?.pages.every((page) => page.items.length === 0) ?? false;
  const emptyNow = useRef(empty);
  useEffect(() => {
    emptyNow.current = empty;
  });
  useFocusEffect(
    useCallback(() => {
      if (emptyNow.current) {
        void refetch();
      }
    }, [refetch]),
  );

  useEffect(() => {
    if (refreshToken > 0) {
      void client.resetQueries({ queryKey: KEY });
    }
  }, [refreshToken, client]);

  // The server tops a page up past posts the viewer may not see, a bounded number of times; a page can
  // still come back empty with more behind it. Then the next one is fetched, instead of showing
  // "nothing here".
  const hasNextPage = feed.hasNextPage;
  const fetchingNext = feed.isFetchingNextPage;
  const fetchNextPage = feed.fetchNextPage;
  const lastPageEmpty = feed.data ? feed.data.pages.at(-1)?.items.length === 0 : false;
  useEffect(() => {
    if (lastPageEmpty && hasNextPage && !fetchingNext) {
      void fetchNextPage();
    }
  }, [lastPageEmpty, hasNextPage, fetchingNext, fetchNextPage]);

  if (feed.isPending) {
    return (
      <View testID="feed-loading">
        <Skeleton height={420} />
      </View>
    );
  }
  if (!feed.data) {
    return (
      <StateMessage
        testID="feed-error"
        title="Could not load your feed"
        onRetry={() => void feed.refetch()}
      />
    );
  }
  const posts = feed.data.pages.flatMap((page) => page.items);
  if (posts.length === 0 && hasNextPage) {
    return (
      <View testID="feed-loading">
        <Skeleton height={420} />
      </View>
    );
  }
  if (posts.length === 0) {
    return (
      <StateMessage
        testID="feed-empty"
        title="Nothing here yet"
        detail="Follow people to see their clips here. Open a gig to find clips, then follow who posted them."
        onRetry={() => void feed.refetch()}
      />
    );
  }
  return (
    <PostPager
      items={posts.map((post, position) => ({ post, position }))}
      surface="FOLLOWING"
      onEndReached={() => {
        if (feed.hasNextPage && !feed.isFetchingNextPage) {
          void feed.fetchNextPage();
        }
      }}
      onGone={() => void client.invalidateQueries({ queryKey: getGetFollowingFeedQueryKey() })}
      onRefresh={() => void pull.refresh()}
      refreshing={pull.refreshing}
      visible={visible}
    />
  );
}
