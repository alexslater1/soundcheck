import { Image } from 'expo-image';
import { Pressable, StyleSheet, View } from 'react-native';

import type { RankingPreviewItem } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { rankChange } from './configModel';

/**
 * One ranked list of the playground: rank, the move against the other list
 * (▲3 / ▼2 / NEW), thumbnail, author, artists and score. Clicking a row opens its waterfall.
 */
export function PreviewColumn({
  title,
  items,
  showChange,
  selectedPostId,
  onSelect,
  testID,
}: {
  title: string;
  items: readonly RankingPreviewItem[];
  /** Whether to show the move against the other list (the draft column does; the active one too). */
  showChange: boolean;
  selectedPostId: string | null;
  onSelect: (item: RankingPreviewItem) => void;
  testID: string;
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.column} testID={testID}>
      <Text variant="label" muted>
        {title.toUpperCase()}
      </Text>
      {items.length === 0 ? (
        <Text muted testID={`${testID}-empty`}>
          No candidates for this user: they follow nobody and nothing is trending or fresh.
        </Text>
      ) : null}
      {items.map((item) => {
        const change = rankChange(item.rank, item.otherRank);
        const changeColour =
          change.kind === 'up'
            ? colors.success
            : change.kind === 'down'
              ? colors.danger
              : change.kind === 'new'
                ? colors.accent2
                : colors.textMuted;
        const selected = selectedPostId === item.post.id;
        return (
          <Pressable
            key={item.post.id}
            accessibilityRole="button"
            accessibilityLabel={`Rank ${item.rank}, @${item.post.author.handle}, score ${item.score.toFixed(3)}${
              showChange ? `, ${change.label}` : ''
            }`}
            onPress={() => onSelect(item)}
            testID={`${testID}-item-${item.rank}`}
            style={[
              styles.row,
              { borderColor: selected ? colors.accent : colors.border },
              selected ? { backgroundColor: colors.accentSoft } : null,
            ]}
          >
            <Text style={styles.rank}>{item.rank}</Text>
            {showChange ? (
              <Text
                variant="caption"
                style={[styles.change, { color: changeColour }]}
                testID={`${testID}-change-${item.rank}`}
              >
                {change.label}
              </Text>
            ) : null}
            <Image
              source={item.post.playback.thumbUrl ?? item.post.playback.posterUrl ?? undefined}
              style={[styles.thumb, { backgroundColor: colors.surfaceRaised }]}
              contentFit="cover"
              accessibilityIgnoresInvertColors
            />
            <View style={styles.text}>
              <Text numberOfLines={1}>@{item.post.author.handle}</Text>
              <Text variant="caption" muted numberOfLines={1}>
                {item.post.artists.map((a) => a.name).join(', ') || item.post.gig?.title || '—'}
              </Text>
            </View>
            {item.exploration ? (
              <Text variant="label" style={{ color: colors.accent2 }}>
                EXPLORE
              </Text>
            ) : null}
            <Text variant="caption" style={styles.score}>
              {item.score.toFixed(3)}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  column: { flex: 1, gap: spacing.xs, minWidth: 0 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: 4,
  },
  rank: { width: 22, textAlign: 'right', fontWeight: '600' },
  change: { width: 36, fontVariant: ['tabular-nums'] },
  thumb: { width: 36, height: 36, borderRadius: radius.sm },
  text: { flex: 1, minWidth: 0 },
  score: { fontVariant: ['tabular-nums'] },
});
