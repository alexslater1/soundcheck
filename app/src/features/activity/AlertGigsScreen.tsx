import { useRouter } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { AlertedGigView } from '@/api/generated/model';
import { useGetNotification } from '@/api/generated/notifications/notifications';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { formatGigDate } from '@/features/gigs/format';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/** Why the fan hears about this gig: an act or venue they follow, or a search they saved. */
export function alertReason(gig: AlertedGigView): string {
  switch (gig.reason) {
    case 'ARTIST':
      return `You follow ${gig.because}`;
    case 'VENUE':
      return `You follow ${gig.because}`;
    case 'SEARCH':
      return `Your saved search “${gig.because}”`;
  }
}

/**
 * A new-gig alert opened from Activity: the new gigs it found, soonest first, each with why the
 * fan hears about it, and a link to the gig page (which shows the live details).
 */
export function AlertGigsScreen({ id }: { id: string }) {
  const { colors } = useTheme();
  const router = useRouter();
  const alert = useGetNotification(id);

  if (alert.isPending) {
    return (
      <Screen>
        <View testID="alert-loading" style={styles.gap}>
          <Skeleton height={28} width="60%" />
          <Skeleton height={64} />
          <Skeleton height={64} />
        </View>
      </Screen>
    );
  }
  if (!alert.data) {
    return (
      <Screen>
        <StateMessage
          testID="alert-error"
          title="Could not load this alert"
          onRetry={() => void alert.refetch()}
        />
      </Screen>
    );
  }
  const gigs = alert.data.gigs ?? [];
  const total = alert.data.gigCount ?? gigs.length;
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap}>
        <Text variant="title">
          {total === 1 ? 'A new gig for you' : `${total} new gigs for you`}
        </Text>
        {gigs.length === 0 ? (
          <Text muted testID="alert-empty">
            The gigs in this alert are no longer listed.
          </Text>
        ) : (
          gigs.map((gig) => (
            <Pressable
              key={gig.gigId}
              accessibilityRole="link"
              accessibilityLabel={`${gig.title}, ${formatGigDate(gig.startsAt)}, ${gig.venueName}`}
              onPress={() => router.push({ pathname: '/gig/[id]', params: { id: gig.gigId } })}
              style={[styles.row, { borderColor: colors.border, backgroundColor: colors.surface }]}
              testID="alert-gig"
            >
              <Text style={styles.title}>{gig.title}</Text>
              <Text muted>
                {formatGigDate(gig.startsAt)} · {gig.venueName}
                {gig.venueCity ? `, ${gig.venueCity}` : ''}
              </Text>
              <Text variant="caption" style={{ color: colors.accent }}>
                {alertReason(gig)}
              </Text>
            </Pressable>
          ))
        )}
        {total > gigs.length ? (
          <Text variant="caption" muted>
            And {total - gigs.length} more: search the Gigs tab to see them all.
          </Text>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  row: {
    minHeight: touchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
  },
  title: { fontWeight: '700' },
});
