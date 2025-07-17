import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useMemo } from 'react';
import { View } from 'react-native';

import {
  getListArtistPostsQueryKey,
  getListVenuePostsQueryKey,
  listArtistPosts,
  listVenuePosts,
} from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { refreshFirstPage, usePullToRefresh } from '@/features/feed/usePullToRefresh';
import { PostPager } from '@/features/posts/PostPager';

export type TaggedKind = 'artist' | 'venue';

/**
 * The clips of one artist or one venue, full screen, as For You shows them: what
 * the Search tab opens when an artist or a venue is picked. It reads the same lists as the artist and
 * venue pages, so a private profile's clips are left out by the server's one rule, and a post looks
 * exactly as it does in the feeds. Newest first; pages follow the server's cursor.
 */
export function TaggedPostsFeed({
  kind,
  id,
  name,
}: {
  kind: TaggedKind;
  id: string;
  name: string;
}) {
  const client = useQueryClient();
  const router = useRouter();
  const key = useMemo(
    () =>
      [
        ...(kind === 'artist' ? getListArtistPostsQueryKey(id) : getListVenuePostsQueryKey(id)),
        'infinite',
      ] as const,
    [kind, id],
  );
  const feed = useInfiniteQuery({
    queryKey: key,
    queryFn: ({ pageParam }) =>
      kind === 'artist'
        ? listArtistPosts(id, { limit: 10, cursor: pageParam })
        : listVenuePosts(id, { limit: 10, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const pull = usePullToRefresh(useCallback(() => refreshFirstPage(client, key), [client, key]));

  // The server tops a page up past posts the viewer may not see a bounded number of times, so a page
  // can come back empty with more behind it: then fetch the next one rather than say "no clips".
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = feed;
  const lastPageEmpty = feed.data ? feed.data.pages.at(-1)?.items.length === 0 : false;
  useEffect(() => {
    if (lastPageEmpty && hasNextPage && !isFetchingNextPage) {
      void fetchNextPage();
    }
  }, [lastPageEmpty, hasNextPage, isFetchingNextPage, fetchNextPage]);

  const openPage = () =>
    router.push(
      kind === 'artist'
        ? { pathname: '/artist/[id]', params: { id } }
        : { pathname: '/venue/[id]', params: { id } },
    );
  const pageLabel = kind === 'artist' ? 'Open the artist page' : 'Open the venue page';

  const posts = feed.data?.pages.flatMap((page) => page.items) ?? [];
  let body;
  if (feed.isPending || (posts.length === 0 && hasNextPage)) {
    body = (
      <View testID="tagged-loading">
        <Skeleton height={420} />
      </View>
    );
  } else if (!feed.data) {
    body = (
      <StateMessage
        testID="tagged-error"
        title="Could not load these clips"
        onRetry={() => void feed.refetch()}
      />
    );
  } else if (posts.length === 0) {
    body = (
      <View testID="tagged-empty">
        <StateMessage
          title={`No clips of ${name} yet`}
          detail={
            kind === 'artist'
              ? 'When someone posts a clip tagged with this artist, it shows here.'
              : 'When someone posts a clip from a gig here, it shows here.'
          }
        />
        <Button title={pageLabel} variant="secondary" onPress={openPage} />
      </View>
    );
  } else {
    body = (
      <PostPager
        items={posts.map((post, position) => ({ post, position }))}
        surface="SEARCH"
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
