import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type ComponentProps } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { NotifyRequestLevel } from '@/api/generated/model';
import { setArtistNotify, setVenueNotify } from '@/api/generated/social/social';
import { IconButton } from '@/components/ui/IconButton';
import { Sheet } from '@/components/ui/Sheet';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget } from '@/theme/tokens';

export type Level = NotifyRequestLevel;

type IconName = ComponentProps<typeof Ionicons>['name'];

/** The four levels in the sheet's order, with what each one means to the follower. */
export const LEVELS: readonly { level: Level; label: string; detail: string }[] = [
  { level: 'ALL', label: 'Everything', detail: 'New gigs and new posts' },
  { level: 'POSTS', label: 'Posts', detail: 'New posts only, at most one alert an hour' },
  { level: 'GIGS', label: 'New gigs', detail: 'New gigs only (the default)' },
  { level: 'NONE', label: 'None', detail: 'Keep following, no alerts' },
];

/** The bell shows the level at a glance: ringing for everything, off for none. */
export function bellIcon(level: Level): IconName {
  return level === 'ALL'
    ? 'notifications'
    : level === 'NONE'
      ? 'notifications-off-outline'
      : 'notifications-outline';
}

/** Query keys that show a follow's level: the artist or venue page and the profile's Artists tab. */
const showsLevel = (key: readonly unknown[]) =>
  typeof key[0] === 'string' &&
  /\/(artists|venues)\/[^/]+$|\/following\/(artists|venues)$/.test(key[0]);

/**
 * The bell beside a followed artist or venue. It opens a sheet of the four levels (Everything,
 * Posts, New gigs, None); the choice shows at once and goes back if the server refuses it.
 */
export function NotifyBell({
  kind,
  id,
  name,
  level,
}: {
  kind: 'artist' | 'venue';
  id: string;
  name: string;
  level: Level;
}) {
  const client = useQueryClient();
  const { colors } = useTheme();
  const [open, setOpen] = useState(false);
  // null = what the server said; a level = our optimistic answer while it catches up.
  const [optimistic, setOptimistic] = useState<Level | null>(null);
  const [failed, setFailed] = useState(false);
  const shown = optimistic ?? level;

  async function choose(next: Level) {
    setOpen(false);
    if (next === shown) return;
    setOptimistic(next);
    setFailed(false);
    try {
      await (kind === 'artist' ? setArtistNotify : setVenueNotify)(id, { level: next });
      await client.invalidateQueries({ predicate: (query) => showsLevel(query.queryKey) });
    } catch {
      setFailed(true);
    } finally {
      setOptimistic(null);
    }
  }

  const current = LEVELS.find((l) => l.level === shown);
  return (
    <View style={styles.bell}>
      <IconButton
        icon={bellIcon(shown)}
        accessibilityLabel={`Alerts for ${name}: ${current?.label ?? shown}. Change`}
        onPress={() => setOpen(true)}
        testID={`notify-bell-${id}`}
      />
      {failed ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          Not saved
        </Text>
      ) : null}
      <Sheet
        visible={open}
        onClose={() => setOpen(false)}
        title={`Alerts for ${name}`}
        testID="notify-sheet"
      >
        {LEVELS.map((option) => {
          const selected = option.level === shown;
          return (
            <Pressable
              key={option.level}
              role="radio"
              aria-checked={selected}
              accessibilityLabel={`${option.label}: ${option.detail}`}
              onPress={() => void choose(option.level)}
              style={({ pressed }) => [
                styles.row,
                { backgroundColor: pressed ? colors.surfaceRaised : 'transparent' },
              ]}
              testID={`notify-${option.level}`}
            >
              <Ionicons
                name={selected ? 'radio-button-on' : 'radio-button-off'}
                size={20}
                color={selected ? colors.accent : colors.textMuted}
              />
              <View style={styles.text}>
                <Text style={styles.bold}>{option.label}</Text>
                <Text variant="caption" muted>
                  {option.detail}
                </Text>
              </View>
            </Pressable>
          );
        })}
      </Sheet>
    </View>
  );
}

const styles = StyleSheet.create({
  bell: { alignItems: 'center' },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: touchTarget,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
  },
  text: { flex: 1 },
  bold: { fontWeight: '700' },
});
