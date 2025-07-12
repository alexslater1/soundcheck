import { Link } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ticketDate } from './ticketDate';

/** The height a ticket takes on the page above a clip (the Ticket feed style). */
export const TICKET_HEIGHT = 64;

const LIGHT = '#FFFFFF';
const LIGHT_MUTED = 'rgba(255,255,255,0.8)';

/**
 * The gig a post is from, like a ticket stub: a date tile with the month, day and
 * year, then the gig and its venue. Tapping it opens the gig, which counts as interest in the post.
 * `onDark` draws it over the clip; otherwise it sits on the page.
 */
export function GigTicket({
  gig,
  postId,
  onDark = false,
}: {
  gig: GigSummary;
  postId: string;
  onDark?: boolean;
}) {
  const { colors } = useTheme();
  const track = useTrack();
  const date = ticketDate(gig.startsAt);
  const where = [gig.venue.name, gig.venue.city].filter(Boolean).join(', ');
  // One flat object: expo-router's <Link asChild> rejects a style array on web.
  const ticketStyle = StyleSheet.flatten([
    styles.ticket,
    onDark
      ? { backgroundColor: 'rgba(0,0,0,0.45)' }
      : { backgroundColor: colors.surface, borderColor: colors.border, borderWidth: 1 },
  ]);

  return (
    <Link
      href={{ pathname: '/gig/[id]', params: { id: gig.id } }}
      onPress={() => track('GIG_TAP', postId)}
      asChild
    >
      <Pressable
        accessibilityRole="link"
        accessibilityLabel={`${gig.title}, ${where}, ${date.day} ${date.month} ${date.year}`}
        style={ticketStyle}
        testID="gig-ticket"
      >
        <View style={[styles.tile, { backgroundColor: colors.accent }]}>
          <Text style={[styles.tileSmall, { color: colors.onAccent }]}>{date.month}</Text>
          <Text style={[styles.tileDay, { color: colors.onAccent }]} testID="gig-ticket-day">
            {date.day}
          </Text>
          <Text style={[styles.tileSmall, { color: colors.onAccent }]}>{date.year}</Text>
        </View>
        {/* The perforation between the stub and the rest of the ticket. */}
        <View
          style={[
            styles.perforation,
            { borderColor: onDark ? 'rgba(255,255,255,0.35)' : colors.border },
          ]}
        />
        <View style={styles.text}>
          <Text style={[styles.title, onDark ? { color: LIGHT } : null]} numberOfLines={1}>
            {gig.title}
          </Text>
          <Text
            variant="caption"
            muted={!onDark}
            style={onDark ? { color: LIGHT_MUTED } : undefined}
            numberOfLines={1}
          >
            {where}
          </Text>
        </View>
      </Pressable>
    </Link>
  );
}

const styles = StyleSheet.create({
  ticket: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    padding: spacing.xs,
    paddingRight: spacing.md,
    borderRadius: radius.md,
    maxWidth: '100%',
    alignSelf: 'flex-start',
  },
  tile: {
    width: 48,
    height: TICKET_HEIGHT - spacing.xs * 2 - 2,
    borderRadius: radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tileSmall: { fontSize: 10, lineHeight: 12, fontWeight: '700', letterSpacing: 0.5 },
  tileDay: { fontSize: 20, lineHeight: 22, fontWeight: '800' },
  perforation: { alignSelf: 'stretch', borderLeftWidth: 1, borderStyle: 'dashed' },
  text: { flexShrink: 1, gap: 2 },
  title: { fontWeight: '700' },
});
