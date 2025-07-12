import { StyleSheet, View } from 'react-native';

import { EqualizerBars } from '@/components/ui/EqualizerBars';
import { Text } from '@/components/ui/Text';
import { spacing } from '@/theme/tokens';

/** How far the feed slides down to show the header. */
export const REFRESH_HEADER_HEIGHT = 96;

/**
 * Sits above the first post of a feed, scrolled out of sight. Pulling the first
 * post down slides the whole feed down and shows it; letting go there refreshes, and the bars dance
 * until the new posts are in, then the feed slides back up.
 */
export function RefreshHeader({ refreshing }: { refreshing: boolean }) {
  return (
    <View
      style={styles.header}
      testID="refresh-header"
      accessibilityLiveRegion="polite"
      accessibilityLabel={refreshing ? 'Finding new clips' : 'Pull down for new clips'}
    >
      <EqualizerBars animating={refreshing} />
      <Text variant="caption" muted>
        {refreshing ? 'Finding new clips…' : 'Let go for new clips'}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  header: {
    height: REFRESH_HEADER_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
  },
});
