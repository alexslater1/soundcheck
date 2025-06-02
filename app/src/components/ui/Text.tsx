import { Text as RNText, StyleSheet, type TextProps as RNTextProps } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { typeScale } from '@/theme/tokens';
import { fontFamily } from '@/theme/typography';

type Variant = keyof typeof typeScale;

export type TextProps = RNTextProps & { variant?: Variant; muted?: boolean };

/**
 * Themed text: one of the type-scale variants, body by default, in the chosen typeface.
 * A weight asked for in `style` picks the matching font file.
 */
export function Text({ variant = 'body', muted = false, style, ...props }: TextProps) {
  const { colors, typeStyle } = useTheme();
  const weight = StyleSheet.flatten([typeScale[variant], style])?.fontWeight;
  const family = fontFamily(typeStyle, variant, weight);
  return (
    <RNText
      {...props}
      style={[
        typeScale[variant],
        { color: muted ? colors.textMuted : colors.text },
        style,
        family ? { fontFamily: family, fontWeight: 'normal' } : null,
      ]}
    />
  );
}
