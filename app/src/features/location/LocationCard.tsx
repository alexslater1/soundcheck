import Ionicons from '@expo/vector-icons/Ionicons';
import { StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import type { Place } from '@/features/gigs/gigFilters';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { askForLocation, declineLocation, useDeviceLocation } from './deviceLocation';

/**
 * The Gigs tab's one question about location (an in-app card first, then Android's own prompt).
 * It says what the location is for before the phone asks, and goes once answered either way; after
 * "Not now", {@link DistanceNote} keeps a small "Use my location" link.
 */
export function LocationCard() {
  const { colors } = useTheme();
  const asked = useDeviceLocation((s) => s.asked);
  const permission = useDeviceLocation((s) => s.permission);
  if (asked || permission === 'unknown' || permission === 'granted') {
    return null;
  }
  return (
    <View
      style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID="location-card"
    >
      <View style={styles.heading}>
        <Ionicons name="navigate-outline" size={18} color={colors.accent} />
        <Text style={styles.bold}>See how far gigs are from you</Text>
      </View>
      <Text variant="caption" muted>
        Soundcheck uses your approximate location, to about a kilometre, only to measure distances.
        It isn&apos;t saved. Without it, distances are from your home city&apos;s centre.
      </Text>
      <View style={styles.buttons}>
        <Button title="Use my location" size="small" onPress={() => void askForLocation()} />
        <Button title="Not now" size="small" variant="secondary" onPress={declineLocation} />
      </View>
    </View>
  );
}

/**
 * Under the place line: where the distances are measured from. With the phone's position: "from where
 * you are"; without it, the centre the server uses (the city picked, else the home city), with a link
 * to turn location on once the card has gone.
 */
export function DistanceNote({ place, homeCity }: { place: Place; homeCity: string | null }) {
  const { colors } = useTheme();
  const here = useDeviceLocation((s) => s.here);
  const asked = useDeviceLocation((s) => s.asked);
  const permission = useDeviceLocation((s) => s.permission);
  if (here) {
    return (
      <Text variant="caption" muted testID="distance-note">
        Distances from where you are
      </Text>
    );
  }
  const centre = place.kind === 'city' ? place.name : homeCity;
  const offer = asked && permission !== 'granted' && permission !== 'unknown';
  if (!centre && !offer) {
    return null;
  }
  return (
    <Text variant="caption" muted testID="distance-note">
      {centre ? `Distances from ${centre}’s centre` : 'No distances without a home city'}
      {offer ? (
        <>
          {' · '}
          <Text
            variant="caption"
            accessibilityRole="button"
            onPress={() => void askForLocation()}
            style={{ color: colors.accent }}
          >
            Use my location
          </Text>
        </>
      ) : null}
    </Text>
  );
}

const styles = StyleSheet.create({
  card: {
    marginTop: spacing.sm,
    padding: spacing.md,
    gap: spacing.sm,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
  },
  heading: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  bold: { fontWeight: '700' },
  buttons: { flexDirection: 'row', gap: spacing.sm },
});
