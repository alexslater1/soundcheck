import { StyleSheet, View } from 'react-native';

import {
  getListUserGoingGigsQueryKey,
  getListUserStatsGigsQueryKey,
  listUserGoingGigs,
  listUserStatsGigs,
} from '@/api/generated/stats/stats';
import { Text } from '@/components/ui/Text';
import { PagedGigs } from '@/features/gigs/PagedGigs';
import { spacing } from '@/theme/tokens';

/**
 * Someone else's Gigs tab: the gigs they are going to, soonest first, then the ones they went to,
 * newest first (the same gigs their stats count). What they marked Interested stays theirs. Shown only
 * where their posts and stats are. Each card carries the viewer's own mark, as everywhere.
 */
export function UserGigs({ userId }: { userId: string }) {
  return (
    <View style={styles.box} testID="user-gigs">
      <Text variant="label" muted>
        GOING
      </Text>
      <PagedGigs
        queryKey={getListUserGoingGigsQueryKey(userId)}
        fetchPage={(cursor) =>
          listUserGoingGigs(userId, { limit: 10, ...(cursor ? { cursor } : {}) })
        }
        empty="No gigs coming up."
        testID="user-gigs-going"
      />
      <Text variant="label" muted style={styles.heading}>
        WENT
      </Text>
      <PagedGigs
        queryKey={getListUserStatsGigsQueryKey(userId)}
        fetchPage={(cursor) =>
          listUserStatsGigs(userId, { limit: 10, ...(cursor ? { cursor } : {}) })
        }
        empty="No gigs marked as Went yet."
        testID="user-gigs-went"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  heading: { marginTop: spacing.md },
});
