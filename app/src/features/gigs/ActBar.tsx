import { Image } from 'expo-image';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { ArtistSummary } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/** "Headliner" for the first act on the bill, "Support" for the others. */
export function billingLabel(index: number): string {
  return index === 0 ? 'Headliner' : 'Support';
}

/**
 * The acts of a gig as a bar of tabs at the top of its page: "Whole night" and then every act on
 * the bill, headliner first. Picking an act shows that act's part of the night (its setlist and the
 * clips tagged with it); support acts get the same space as the headliner, because fans often go for
 * them.
 */
export function ActBar({
  acts,
  selected,
  onSelect,
  stuck = false,
}: {
  acts: readonly ArtistSummary[];
  /** The picked act's id, or null for the whole night. */
  selected: string | null;
  onSelect: (artistId: string | null) => void;
  /**
   * The page has scrolled under the bar: it gets the page's colour and a hairline so the content
   * scrolls up to a clean edge. At rest it is transparent, so the screen's glow shows through.
   */
  stuck?: boolean;
}) {
  const { colors } = useTheme();
  return (
    <View
      style={[
        styles.bar,
        {
          backgroundColor: stuck ? colors.background : 'transparent',
          borderBottomColor: stuck ? colors.border : 'transparent',
        },
      ]}
      testID="act-bar"
    >
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.row}
        accessibilityRole="tablist"
      >
        <Tab label="Whole night" selected={selected === null} onPress={() => onSelect(null)} />
        {acts.map((act, index) => (
          <Tab
            key={act.id}
            label={act.name}
            detail={billingLabel(index)}
            imageUrl={act.imageUrl ?? null}
            selected={selected === act.id}
            onPress={() => onSelect(act.id)}
          />
        ))}
      </ScrollView>
    </View>
  );
}

function Tab({
  label,
  detail,
  imageUrl,
  selected,
  onPress,
}: {
  label: string;
  detail?: string;
  imageUrl?: string | null;
  selected: boolean;
  onPress: () => void;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      role="tab"
      aria-selected={selected}
      accessibilityLabel={detail ? `${label}, ${detail.toLowerCase()}` : label}
      onPress={onPress}
      style={[
        styles.tab,
        {
          backgroundColor: selected ? colors.accent : colors.surface,
          borderColor: selected ? colors.accent : colors.border,
        },
      ]}
      testID="act-tab"
    >
      {imageUrl !== undefined ? (
        <View style={[styles.avatar, { backgroundColor: colors.accentSoft }]}>
          {imageUrl ? (
            <Image
              source={{ uri: imageUrl }}
              style={StyleSheet.absoluteFill}
              contentFit="cover"
              accessibilityIgnoresInvertColors
            />
          ) : (
            <Text variant="caption" style={{ fontWeight: '800', color: colors.accent }}>
              {label.slice(0, 1).toUpperCase()}
            </Text>
          )}
        </View>
      ) : null}
      <View>
        <Text
          numberOfLines={1}
          style={[styles.label, { color: selected ? colors.onAccent : colors.text }]}
        >
          {label}
        </Text>
        {detail ? (
          <Text
            variant="caption"
            style={{ color: selected ? colors.onAccent : colors.textMuted }}
            numberOfLines={1}
          >
            {detail}
          </Text>
        ) : null}
      </View>
    </Pressable>
  );
}

const AVATAR = 28;

const styles = StyleSheet.create({
  bar: { paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth },
  row: { gap: spacing.sm },
  tab: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: touchTarget,
    maxWidth: 220,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
  },
  avatar: {
    width: AVATAR,
    height: AVATAR,
    borderRadius: AVATAR / 2,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  label: { fontWeight: '600' },
});
