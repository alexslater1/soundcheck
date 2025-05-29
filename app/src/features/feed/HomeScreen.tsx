import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { HealthBadge } from '@/features/health/HealthBadge';
import { spacing } from '@/theme/tokens';

import { FollowingFeed } from './FollowingFeed';
import { ForYouFeed } from './ForYouFeed';

type Tab = 'following' | 'forYou';

/**
 * Home: "For You | Following". For You is the ranked feed; pressing it again while it is
 * showing asks for a fresh one (the web has no pull-to-refresh).
 */
export function HomeScreen() {
  const [tab, setTab] = useState<Tab>('forYou');
  const [refreshToken, setRefreshToken] = useState(0);
  return (
    <Screen wide>
      <View style={styles.header}>
        <Text variant="title">Soundcheck</Text>
        <HealthBadge />
      </View>
      <View style={styles.tabs} accessibilityRole="radiogroup">
        <Chip
          label="For You"
          selected={tab === 'forYou'}
          onPress={() => {
            if (tab === 'forYou') {
              setRefreshToken((token) => token + 1);
            }
            setTab('forYou');
          }}
        />
        <Chip
          label="Following"
          selected={tab === 'following'}
          onPress={() => setTab('following')}
        />
      </View>
      <View style={styles.feed}>
        {tab === 'following' ? <FollowingFeed /> : <ForYouFeed refreshToken={refreshToken} />}
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
