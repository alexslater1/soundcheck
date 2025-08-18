import { Pressable, ScrollView, StyleSheet } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import type { ActSegment } from './actBar';

type Props = {
  segments: readonly ActSegment[];
  /** The act of the clip in view; null when that clip has no act. */
  current: string | null;
  onPick: (actId: string) => void;
  /** Drawn over the clip: light chips whatever the theme. */
  onDark?: boolean;
};

/**
 * One chip per act of a post's clips: tapping one goes to that act's first clip, and the
 * chip of the clip in view is highlighted as the carousel moves from one act's clips to the next.
 */
export function ActBar({ segments, current, onPick, onDark = false }: Props) {
  const { colors } = useTheme();
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={styles.bar}
      testID="act-bar"
    >
      {segments.map((segment) => {
        const selected = segment.actId === current;
        const background = selected
          ? colors.accent
          : onDark
            ? 'rgba(0,0,0,0.45)'
            : colors.surfaceRaised;
        const ink = selected ? colors.onAccent : onDark ? '#FFFFFF' : colors.text;
        return (
          <Pressable
            key={segment.actId}
            accessibilityRole="button"
            accessibilityState={{ selected }}
            accessibilityLabel={`${segment.name}, ${segment.count} ${segment.count === 1 ? 'clip' : 'clips'}`}
            onPress={() => onPick(segment.actId)}
            style={[styles.chip, { backgroundColor: background }]}
            testID={`act-chip-${segment.actId}`}
          >
            <Text variant="caption" style={[styles.name, { color: ink }]} numberOfLines={1}>
              {segment.name}
            </Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  bar: { gap: spacing.xs, paddingHorizontal: spacing.xs },
  chip: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    maxWidth: 180,
  },
  name: { fontWeight: '700' },
});
