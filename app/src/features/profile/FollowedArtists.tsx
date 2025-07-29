import { useInfiniteQuery, type QueryKey } from '@tanstack/react-query';
import { Link } from 'expo-router';
import type { ReactNode } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import type { FollowedArtist, FollowedVenue } from '@/api/generated/model';
import {
  getListFollowedArtistsQueryKey,
  getListFollowedVenuesQueryKey,
  listFollowedArtists,
  listFollowedVenues,
} from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { ArtistRow } from '@/features/artists/ArtistRow';
import { useGenreNames } from '@/features/artists/useGenreNames';
import { NotifyBell } from '@/features/follows/NotifyBell';
import { VenueFollowButton } from '@/features/venues/VenueFollowButton';
import { spacing, touchTarget } from '@/theme/tokens';

type Page<T> = { items: T[]; nextCursor?: string | null };

/**
 * A profile's Artists tab: the artists it follows, then a Venues section, newest follow first, a
 * page at a time. Each row's button is the viewer's own follow (on one's own profile, "Following" taps
 * to unfollow); on one's own profile a bell beside it sets what that follow alerts about.
 */
export function FollowedArtists({ userId, own }: { userId: string; own: boolean }) {
  const genreNames = useGenreNames();
  return (
    <View style={styles.box} testID="followed">
      <Section<FollowedArtist>
        title="Artists"
        queryKey={getListFollowedArtistsQueryKey(userId)}
        fetchPage={(cursor) =>
          listFollowedArtists(userId, { limit: 20, ...(cursor ? { cursor } : {}) })
        }
        keyOf={(row) => row.artist.id}
        empty={
          own ? 'You follow no artists yet. Follow one from its page.' : 'Follows no artists yet.'
        }
        testID="followed-artists"
        renderRow={(row) => (
          <ArtistRow
            artist={row.artist}
            genreNames={genreNames}
            trailing={
              own && row.notifyLevel ? (
                <NotifyBell
                  kind="artist"
                  id={row.artist.id}
                  name={row.artist.name}
                  level={row.notifyLevel}
                />
              ) : null
            }
          />
        )}
      />
      <Section<FollowedVenue>
        title="Venues"
        queryKey={getListFollowedVenuesQueryKey(userId)}
        fetchPage={(cursor) =>
          listFollowedVenues(userId, { limit: 20, ...(cursor ? { cursor } : {}) })
        }
        keyOf={(row) => row.venue.id}
        empty={
          own ? 'You follow no venues yet. Follow one from its page.' : 'Follows no venues yet.'
        }
        testID="followed-venues"
        renderRow={(row) => (
          <View style={styles.row} testID="venue-row">
            <Link href={`/venue/${row.venue.id}`} asChild>
              <Pressable accessibilityRole="link" style={styles.fill}>
                <Text numberOfLines={1} style={styles.name}>
                  {row.venue.name}
                </Text>
                {row.venue.city ? (
                  <Text variant="caption" muted numberOfLines={1}>
                    {row.venue.city}
                  </Text>
                ) : null}
              </Pressable>
            </Link>
            <VenueFollowButton
              venueId={row.venue.id}
              venueName={row.venue.name}
              isFollowing={row.isFollowing}
              compact
            />
            {own && row.notifyLevel ? (
              <NotifyBell
                kind="venue"
                id={row.venue.id}
                name={row.venue.name}
                level={row.notifyLevel}
              />
            ) : null}
          </View>
        )}
      />
    </View>
  );
}

function Section<T>({
  title,
  queryKey,
  fetchPage,
  keyOf,
  renderRow,
  empty,
  testID,
}: {
  title: string;
  queryKey: QueryKey;
  fetchPage: (cursor: string | undefined) => Promise<Page<T>>;
  keyOf: (row: T) => string;
  renderRow: (row: T) => ReactNode;
  empty: string;
  testID: string;
}) {
  const list = useInfiniteQuery({
    // The generated key is the list's path, so the follow buttons' refresh rules reach it.
    queryKey: [...queryKey, 'infinite'],
    queryFn: ({ pageParam }) => fetchPage(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const rows = list.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <View style={styles.box} testID={testID}>
      <Text variant="label" muted>
        {title.toUpperCase()}
      </Text>
      {list.isPending ? (
        <Skeleton height={touchTarget * 2} />
      ) : !list.data ? (
        <StateMessage
          title={`Could not load ${title.toLowerCase()}`}
          onRetry={() => void list.refetch()}
        />
      ) : rows.length === 0 ? (
        <Text muted testID={`${testID}-empty`}>
          {empty}
        </Text>
      ) : (
        <View>
          {rows.map((row) => (
            <View key={keyOf(row)}>{renderRow(row)}</View>
          ))}
          {list.hasNextPage ? (
            <Button
              title={list.isFetchingNextPage ? 'Loading…' : `Show more ${title.toLowerCase()}`}
              variant="secondary"
              disabled={list.isFetchingNextPage}
              onPress={() => void list.fetchNextPage()}
            />
          ) : null}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: touchTarget + spacing.sm,
    paddingVertical: spacing.xs,
  },
  fill: { flex: 1 },
  name: { fontWeight: '600' },
});
