import { Text as RNText, type TextProps as RNTextProps } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { typeScale } from '@/theme/tokens';

type Variant = keyof typeof typeScale;

export type TextProps = RNTextProps & { variant?: Variant; muted?: boolean };

/** Themed text: one of the type-scale variants, body by default. */
export function Text({ variant = 'body', muted = false, style, ...props }: TextProps) {
  const { colors } = useTheme();
  return (
    <RNText
      {...props}
      style={[typeScale[variant], { color: muted ? colors.textMuted : colors.text }, style]}
    />
  );
}
