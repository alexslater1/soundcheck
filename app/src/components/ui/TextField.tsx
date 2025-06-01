import { forwardRef } from 'react';
import { StyleSheet, TextInput, type TextInputProps } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/** A themed single-line text box, 44 pt tall. */
export const TextField = forwardRef<TextInput, TextInputProps>(function TextField(
  { style, ...props },
  ref,
) {
  const { colors } = useTheme();
  return (
    <TextInput
      ref={ref}
      placeholderTextColor={colors.textMuted}
      {...props}
      style={[
        styles.input,
        { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
        style,
      ]}
    />
  );
});

const styles = StyleSheet.create({
  input: {
    minHeight: touchTarget,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    fontSize: 16,
  },
});
