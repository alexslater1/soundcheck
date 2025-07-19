import Ionicons from '@expo/vector-icons/Ionicons';
import { Pressable, StyleSheet } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { Text } from './Text';

/**
 * A pill-shaped choice used for filters (date ranges, genres) and attendance. Exposed to assistive
 * technology as a radio button with a checked state: `role` and `aria-checked` are understood by React
 * Native on Android and forwarded to the DOM by React Native Web (which ignores `accessibilityState`).
 */
export function Chip({
  label,
  selected,
  onPress,
  disabled = false,
  icon,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
  disabled?: boolean;
  /** Optional Ionicons name shown before the label (decorative: the label names the chip). */
  icon?: keyof typeof Ionicons.glyphMap;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      role="radio"
      aria-checked={selected}
      aria-disabled={disabled}
      disabled={disabled}
      onPress={onPress}
      style={[
        styles.chip,
        {
          backgroundColor: selected ? colors.accent : colors.surface,
          borderColor: selected ? colors.accent : colors.border,
          opacity: disabled ? 0.6 : 1,
        },
      ]}
    >
      {icon ? (
        <Ionicons
          name={icon}
          size={16}
          color={selected ? colors.onAccent : colors.text}
          style={styles.icon}
          testID="chip-icon"
          accessibilityElementsHidden
          importantForAccessibility="no"
        />
      ) : null}
      <Text
        variant="caption"
        style={{
          color: selected ? colors.onAccent : colors.text,
          fontWeight: selected ? '700' : '400',
        }}
      >
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  chip: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    marginRight: spacing.sm,
  },
  icon: { marginRight: spacing.xs },
});
