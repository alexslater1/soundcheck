import { Stack } from 'expo-router';
import { ScrollView, StyleSheet } from 'react-native';

import { getListUserStatsGigsQueryKey, listUserStatsGigs } from '@/api/generated/stats/stats';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { PagedGigs } from '@/features/gigs/PagedGigs';
import { spacing } from '@/theme/tokens';

/**
 * The gigs behind one line of someone's stats: the gigs they went to with that act on the
 * bill, or at that venue, newest first. The title is the act's or venue's name, passed by the line.
 */
export function StatsGigsScreen({
  userId,
  title,
  artistId,
  venueId,
}: {
  userId: string;
  title: string;
  artistId?: string;
  venueId?: string;
}) {
  const params = {
    ...(artistId ? { artistId } : {}),
    ...(venueId ? { venueId } : {}),
  };
  return (
    <Screen>
      <Stack.Screen options={{ title }} />
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="label" muted>
          {artistId ? 'EVERY TIME SEEN' : venueId ? 'EVERY VISIT' : 'EVERY GIG'}
        </Text>
        <PagedGigs
          queryKey={getListUserStatsGigsQueryKey(userId, params)}
          fetchPage={(cursor) =>
            listUserStatsGigs(userId, { ...params, limit: 20, ...(cursor ? { cursor } : {}) })
          }
          empty="No gigs here any more."
          testID="stats-gigs"
        />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.sm, paddingBottom: spacing.xl },
});
