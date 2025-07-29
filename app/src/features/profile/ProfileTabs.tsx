import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget } from '@/theme/tokens';

export type ProfileTab = 'posts' | 'gigs' | 'artists' | 'stats';

const TABS: readonly ProfileTab[] = ['posts', 'gigs', 'artists', 'stats'];

/**
 * The tabs under a profile's header: Posts · My gigs · Artists · Stats on one's own, Posts ·
 * Gigs · Artists · Stats on someone else's. Equal widths; the chosen one is underlined.
 */
export function ProfileTabs({
  value,
  onChange,
  gigsLabel,
}: {
  value: ProfileTab;
  onChange: (tab: ProfileTab) => void;
  gigsLabel: string;
}) {
  const { colors } = useTheme();
  const labels: Record<ProfileTab, string> = {
    posts: 'Posts',
    gigs: gigsLabel,
    artists: 'Artists',
    stats: 'Stats',
  };
  return (
    <View
      accessibilityRole="tablist"
      style={[styles.row, { borderBottomColor: colors.border }]}
      testID="profile-tabs"
    >
      {TABS.map((tab) => {
        const selected = tab === value;
        return (
          <Pressable
            key={tab}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            onPress={() => onChange(tab)}
            style={[styles.tab, { borderBottomColor: selected ? colors.accent : 'transparent' }]}
            testID={`profile-tab-${tab}`}
          >
            <Text
              variant="label"
              style={{ color: selected ? colors.text : colors.textMuted, fontWeight: '700' }}
            >
              {labels[tab].toUpperCase()}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', borderBottomWidth: StyleSheet.hairlineWidth },
  tab: {
    flex: 1,
    minHeight: touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderBottomWidth: 2,
    marginBottom: -StyleSheet.hairlineWidth,
    paddingHorizontal: spacing.xs,
  },
});
