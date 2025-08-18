import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import { View } from 'react-native';

import {
  getListMyPostsQueryKey,
  getListUserPostsQueryKey,
  listMyPosts,
  listUserPosts,
} from '@/api/generated/posts/posts';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { refreshFirstPage, usePullToRefresh } from '@/features/feed/usePullToRefresh';
import { PostPager } from '@/features/posts/PostPager';

/** As many posts a page as the profile's grid shows, so the post tapped there is on the first page. */
export const PROFILE_PAGE = 30;

/**
 * A profile's posts, full screen, one post a page: what tapping a post in a profile's grid opens, like
 * Search's artist posts. Up and down change the post, left and right the clip. It reads the same list
 * as the grid (your own posts on Me, anyone's on their profile), so a private profile's posts are left
 * are left out by the same server rule; later pages follow the server's cursor.
 */
export function ProfilePostsFeed({
  userId,
  mine,
  startPostId,
}: {
  userId: string;
  /** Your own profile (Me): its list includes your posts still on their way. */
  mine: boolean;
  startPostId?: string;
}) {
  const client = useQueryClient();
  const key = useMemo(
    () =>
      [
        ...(mine ? getListMyPostsQueryKey() : getListUserPostsQueryKey(userId)),
        'infinite',
      ] as const,
    [mine, userId],
  );
  const feed = useInfiniteQuery({
    queryKey: key,
    queryFn: ({ pageParam }) =>
      mine
        ? listMyPosts({ limit: PROFILE_PAGE, cursor: pageParam })
        : listUserPosts(userId, { limit: PROFILE_PAGE, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const pull = usePullToRefresh(useCallback(() => refreshFirstPage(client, key), [client, key]));
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = feed;

  const posts = feed.data?.pages.flatMap((page) => page.items) ?? [];
  let body;
  if (feed.isPending) {
    body = (
      <View testID="profile-posts-loading">
        <Skeleton height={420} />
      </View>
    );
  } else if (!feed.data) {
    body = (
      <StateMessage
        testID="profile-posts-error"
        title="Could not load the posts"
        onRetry={() => void feed.refetch()}
      />
    );
  } else if (posts.length === 0) {
    body = <StateMessage testID="profile-posts-empty" title="No clips yet" />;
  } else {
    body = (
      <PostPager
        items={posts.map((post, position) => ({ post, position }))}
        surface="PROFILE"
        // The tapped post; the pager takes it once, when it first shows.
        initialIndex={Math.max(
          0,
          posts.findIndex((post) => post.id === startPostId),
        )}
        onEndReached={() => {
          if (hasNextPage && !isFetchingNextPage) {
            void fetchNextPage();
          }
        }}
        onGone={() => void client.invalidateQueries({ queryKey: key })}
        onRefresh={() => void pull.refresh()}
        refreshing={pull.refreshing}
      />
    );
  }
  return <Screen wide>{body}</Screen>;
}
