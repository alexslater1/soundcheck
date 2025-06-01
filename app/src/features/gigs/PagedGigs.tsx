import { useInfiniteQuery, type QueryKey } from '@tanstack/react-query';
import { View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';

import { GigCard } from './GigCard';

type Page = { items: GigSummary[]; nextCursor?: string | null };

/**
 * A list of gigs that loads more on request ("Show more gigs"), following the server's cursor. Used on
 * artist and venue pages, which used to stop after one page with no way to see the rest of a tour.
 */
export function PagedGigs({
  queryKey,
  fetchPage,
  empty,
  testID,
}: {
  queryKey: QueryKey;
  fetchPage: (cursor: string | undefined) => Promise<Page>;
  empty: string;
  testID?: string;
}) {
  const list = useInfiniteQuery({
    queryKey: [...queryKey, 'infinite'],
    queryFn: ({ pageParam }) => fetchPage(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  if (list.isPending) {
    return <Skeleton height={96} />;
  }
  if (!list.data) {
    return <StateMessage title="Could not load gigs" onRetry={() => void list.refetch()} />;
  }
  const gigs = list.data.pages.flatMap((page) => page.items);
  if (gigs.length === 0) {
    return (
      <Text muted testID={testID ? `${testID}-empty` : undefined}>
        {empty}
      </Text>
    );
  }
  return (
    <View testID={testID}>
      {gigs.map((gig) => (
        <GigCard key={gig.id} gig={gig} />
      ))}
      {list.hasNextPage ? (
        <Button
          title={list.isFetchingNextPage ? 'Loading…' : 'Show more gigs'}
          variant="secondary"
          disabled={list.isFetchingNextPage}
          onPress={() => void list.fetchNextPage()}
        />
      ) : null}
      {list.isError ? (
        <StateMessage title="Could not load more gigs" onRetry={() => void list.fetchNextPage()} />
      ) : null}
    </View>
  );
}
