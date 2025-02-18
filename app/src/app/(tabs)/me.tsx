import { Link } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/** Own profile: header now; clip grid and attendance tabs come later. */
export default function MeScreen() {
  const { colors } = useTheme();
  const me = useAuthStore((s) => s.me);
  if (!me) {
    return (
      <Screen>
        <Text muted>Loading profile…</Text>
      </Screen>
    );
  }
  return (
    <Screen>
      <View
        style={[styles.header, { backgroundColor: colors.surface, borderColor: colors.border }]}
      >
        <View style={[styles.avatar, { backgroundColor: colors.accentMuted }]}>
          <Text variant="title" style={{ color: '#FFFFFF' }}>
            {me.displayName.slice(0, 1).toUpperCase()}
          </Text>
        </View>
        <Text variant="title">{me.displayName}</Text>
        <Text muted>@{me.handle}</Text>
        {me.bio ? <Text>{me.bio}</Text> : null}
        <Text variant="caption" muted>
          {me.homeCity ? me.homeCity.name : 'No city yet'} · {me.genres.length} genres
        </Text>
        <View style={styles.counts}>
          {(
            [
              ['Posts', me.counts.posts],
              ['Followers', me.counts.followers],
              ['Following', me.counts.following],
            ] as const
          ).map(([label, n]) => (
            <View key={label} style={styles.count}>
              <Text variant="title">{n}</Text>
              <Text variant="caption" muted>
                {label}
              </Text>
            </View>
          ))}
        </View>
      </View>
      <Link href="/settings" style={styles.link}>
        <Text muted>Settings →</Text>
      </Link>
      <Text muted style={styles.hint}>
        Your clips appear here.
      </Text>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    padding: spacing.md,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    gap: spacing.xs,
  },
  avatar: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
  counts: { flexDirection: 'row', gap: spacing.lg, marginTop: spacing.sm },
  count: { alignItems: 'center' },
  link: { marginTop: spacing.md },
  hint: { marginTop: spacing.lg },
});
