import { Pressable, StyleSheet, type PressableProps } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { Text } from './Text';

type Props = PressableProps & {
  title: string;
  /**
   * primary: the one main action of a screen (solid accent). outline: an action offered many times in a
   * list, like Follow (accent border: a column of solid buttons drowned the list). secondary
   * and danger as before.
   */
  variant?: 'primary' | 'secondary' | 'outline' | 'danger';
  /** small: 36 pt tall inside rows; the touch area is still 44 pt (hitSlop). */
  size?: 'regular' | 'small';
};

/** A pill button in the four tones the app uses. */
export function Button({
  title,
  variant = 'primary',
  size = 'regular',
  disabled,
  style,
  ...props
}: Props) {
  const { colors } = useTheme();
  const background =
    variant === 'primary'
      ? colors.accent
      : variant === 'danger'
        ? colors.danger
        : variant === 'outline'
          ? 'transparent'
          : colors.surfaceRaised;
  const color =
    variant === 'secondary'
      ? colors.text
      : variant === 'outline'
        ? colors.accent
        : variant === 'danger'
          ? '#FFFFFF'
          : colors.onAccent;
  const small = size === 'small';
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      hitSlop={small ? (touchTarget - SMALL_HEIGHT) / 2 : undefined}
      {...props}
      style={({ pressed }) => [
        styles.button,
        small ? styles.small : null,
        {
          backgroundColor: background,
          borderColor: variant === 'outline' ? colors.accent : 'transparent',
          opacity: disabled ? 0.5 : pressed ? 0.8 : 1,
        },
        style as never,
      ]}
    >
      <Text variant={small ? 'caption' : 'body'} style={{ color, fontWeight: '700' }}>
        {title}
      </Text>
    </Pressable>
  );
}

const SMALL_HEIGHT = 36;

const styles = StyleSheet.create({
  button: {
    minHeight: touchTarget,
    borderRadius: radius.pill,
    borderWidth: 1.5,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
  small: { minHeight: SMALL_HEIGHT, paddingHorizontal: spacing.md },
});
