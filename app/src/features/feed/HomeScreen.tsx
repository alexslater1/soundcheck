import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { HealthBadge } from '@/features/health/HealthBadge';
import { spacing } from '@/theme/tokens';

import { FollowingFeed } from './FollowingFeed';

type Tab = 'following' | 'forYou';

/**
 * Home: "For You | Following". The ranked For You feed is not built yet; until it exists the screen
 * opens on Following, which is real.
 */
export function HomeScreen() {
  const [tab, setTab] = useState<Tab>('following');
  return (
    <Screen>
      <View style={styles.header}>
        <Text variant="title">Soundcheck</Text>
        <HealthBadge />
      </View>
      <View style={styles.tabs} accessibilityRole="radiogroup">
        <Chip
          label="Following"
          selected={tab === 'following'}
          onPress={() => setTab('following')}
        />
        <Chip label="For You" selected={tab === 'forYou'} onPress={() => setTab('forYou')} />
      </View>
      <View style={styles.feed}>
        {tab === 'following' ? (
          <FollowingFeed />
        ) : (
          <StateMessage
            testID="for-you-placeholder"
            title="For You is coming"
            detail="The ranked feed is built in the next phase. Following shows the people you follow."
          />
        )}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  tabs: { flexDirection: 'row', gap: spacing.sm, marginVertical: spacing.sm },
  feed: { flex: 1 },
});
