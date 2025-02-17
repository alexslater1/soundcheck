import { Link } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { HealthBadge } from '@/features/health/HealthBadge';
import { spacing } from '@/theme/tokens';

/** Home placeholder: shows the API health fetched through the edge. */
export default function HomeScreen() {
  return (
    <Screen>
      <View style={styles.header}>
        <Text variant="display">Soundcheck</Text>
        <Text muted>Gigs near you and clips from the crowd.</Text>
      </View>
      <HealthBadge />
      <Link href="/console" style={styles.link}>
        <Text variant="caption" muted>
          Admin console →
        </Text>
      </Link>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { gap: spacing.xs, marginBottom: spacing.lg },
  link: { marginTop: spacing.lg },
});
