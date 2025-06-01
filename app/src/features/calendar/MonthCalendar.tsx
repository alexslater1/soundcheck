import Ionicons from '@expo/vector-icons/Ionicons';
import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { addMonths, monthGrid, monthTitle, type Month } from './monthGrid';

const WEEKDAYS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

type Props = {
  month: Month;
  onMonthChange: (month: Month) => void;
  /** `YYYY-MM-DD` → how many gigs that day; days with any are highlighted. */
  counts: Record<string, number>;
  selected: string | null;
  onSelect: (date: string | null) => void;
  /** `YYYY-MM-DD` of today on the UK calendar (ringed). */
  today: string;
  /** Shown while the counts are loading. */
  loading?: boolean;
  testID?: string;
};

/**
 * A month of days, Monday first. Days with gigs are tinted and show how many; tapping one selects it
 * (tapping it again clears the selection). Used by the Gigs tab (gigs matching the search) and the
 * Me tab (the gigs the user marked).
 */
export function MonthCalendar({
  month,
  onMonthChange,
  counts,
  selected,
  onSelect,
  today,
  loading = false,
  testID,
}: Props) {
  const { colors } = useTheme();
  const weeks = monthGrid(month);
  return (
    <View
      style={[styles.box, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID={testID}
    >
      <View style={styles.header}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Previous month"
          onPress={() => onMonthChange(addMonths(month, -1))}
          style={styles.arrow}
        >
          <Ionicons name="chevron-back" size={20} color={colors.text} />
        </Pressable>
        <Text style={styles.title} testID="calendar-title">
          {monthTitle(month)}
          {loading ? ' …' : ''}
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Next month"
          onPress={() => onMonthChange(addMonths(month, 1))}
          style={styles.arrow}
        >
          <Ionicons name="chevron-forward" size={20} color={colors.text} />
        </Pressable>
      </View>
      <View style={styles.week}>
        {WEEKDAYS.map((day, i) => (
          <Text key={i} variant="label" muted style={styles.weekday}>
            {day}
          </Text>
        ))}
      </View>
      {weeks.map((week) => (
        <View key={week[0]!.date} style={styles.week}>
          {week.map((cell) => {
            const count = counts[cell.date] ?? 0;
            const isSelected = cell.date === selected;
            const background = isSelected
              ? colors.accent
              : count > 0
                ? colors.accentSoft
                : 'transparent';
            const ink = isSelected
              ? colors.onAccent
              : cell.inMonth
                ? colors.text
                : colors.textMuted;
            return (
              <Pressable
                key={cell.date}
                accessibilityRole="button"
                accessibilityLabel={`${cell.date}${count > 0 ? `, ${count} gig${count === 1 ? '' : 's'}` : ''}`}
                accessibilityState={{ selected: isSelected }}
                testID={`day-${cell.date}`}
                onPress={() => onSelect(isSelected ? null : cell.date)}
                style={[
                  styles.day,
                  {
                    backgroundColor: background,
                    borderColor: cell.date === today ? colors.accent2 : 'transparent',
                    opacity: cell.inMonth ? 1 : 0.45,
                  },
                ]}
              >
                <Text style={[styles.dayNumber, { color: ink }]}>{cell.day}</Text>
                {count > 0 ? (
                  <Text
                    variant="label"
                    style={{ color: isSelected ? colors.onAccent : colors.accent }}
                  >
                    {count > 99 ? '99+' : count}
                  </Text>
                ) : (
                  <Text variant="label"> </Text>
                )}
              </Pressable>
            );
          })}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  box: {
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    padding: spacing.sm,
    gap: 2,
  },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  arrow: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  title: { fontWeight: '700' },
  week: { flexDirection: 'row', gap: 2 },
  weekday: { flex: 1, textAlign: 'center', paddingVertical: spacing.xs },
  day: {
    flex: 1,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.sm,
    borderWidth: 2,
  },
  dayNumber: { fontWeight: '600' },
});
