import { Pressable, StyleSheet, type PressableProps } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { Text } from './Text';

type Props = PressableProps & { title: string; variant?: 'primary' | 'secondary' | 'danger' };

/** 44pt-tall button in the three tones the app uses. */
export function Button({ title, variant = 'primary', disabled, style, ...props }: Props) {
  const { colors } = useTheme();
  const background =
    variant === 'primary'
      ? colors.accent
      : variant === 'danger'
        ? colors.danger
        : colors.surfaceRaised;
  const color = variant === 'secondary' ? colors.text : '#FFFFFF';
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      {...props}
      style={[
        styles.button,
        { backgroundColor: background, opacity: disabled ? 0.5 : 1 },
        style as never,
      ]}
    >
      <Text style={{ color, fontWeight: '700' }}>{title}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: touchTarget,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
});
