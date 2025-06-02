import Ionicons from '@expo/vector-icons/Ionicons';
import type { ComponentProps } from 'react';
import { Pressable, StyleSheet, type PressableProps } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius, touchTarget } from '@/theme/tokens';

type Props = PressableProps & {
  icon: ComponentProps<typeof Ionicons>['name'];
  /** Said by screen readers; an icon alone has no text. */
  accessibilityLabel: string;
};

/** A round 44pt button with one icon, for secondary actions beside a main one (the ⋯ menu). */
export function IconButton({ icon, disabled, style, ...props }: Props) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      hitSlop={4}
      {...props}
      style={[
        styles.button,
        { backgroundColor: colors.surfaceRaised, opacity: disabled ? 0.5 : 1 },
        style as never,
      ]}
    >
      <Ionicons name={icon} size={20} color={colors.text} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    width: touchTarget,
    height: touchTarget,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
