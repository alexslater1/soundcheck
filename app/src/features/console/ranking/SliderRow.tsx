import { useState } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { clampInput, snap, type Range } from './configModel';
import { RangeInput } from './RangeInput';

/**
 * One weight or param of the playground: its name, a slider and a number box that edit the same value
 *. The box takes what the server accepts, which can be wider than the slider (a lab-trained
 * weight of 7 shows the slider at its end and 7 in the box). A locked row says why it is locked.
 */
export function SliderRow({
  name,
  label,
  value,
  range,
  changed,
  lockedReason,
  onChange,
}: {
  name: string;
  label: string;
  value: number;
  range: Range;
  /** True when the value differs from the stored config's, so the row is marked. */
  changed: boolean;
  lockedReason?: string;
  onChange: (value: number) => void;
}) {
  const { colors } = useTheme();
  // While the box is being typed in it shows what is typed; otherwise it follows the slider (and Reset).
  const [draft, setDraft] = useState<string | null>(null);
  const text = draft ?? String(value);

  const commit = (raw: string) => {
    const next = clampInput(raw, range);
    if (next !== null) onChange(snap(next, range.step));
    setDraft(null);
  };

  return (
    <View style={styles.row} testID={`slider-${name}`}>
      <View style={styles.labelBox}>
        <Text variant="caption" numberOfLines={1} style={changed ? { color: colors.accent } : null}>
          {changed ? '● ' : ''}
          {label}
        </Text>
        {lockedReason ? (
          <Text variant="caption" muted testID={`slider-${name}-locked`}>
            {lockedReason}
          </Text>
        ) : null}
      </View>
      <RangeInput
        value={value}
        min={range.min}
        max={range.max}
        step={range.step}
        label={label}
        accent={colors.accent}
        disabled={Boolean(lockedReason)}
        onChange={(v) => onChange(snap(v, range.step))}
        testID={`slider-${name}-range`}
      />
      <TextInput
        accessibilityLabel={`${label} value`}
        testID={`slider-${name}-input`}
        editable={!lockedReason}
        value={text}
        inputMode="decimal"
        onFocus={() => setDraft(String(value))}
        onChangeText={(t) => {
          setDraft(t);
          const next = clampInput(t, range);
          if (next !== null && next === Number(t.replace(',', '.')))
            onChange(snap(next, range.step));
        }}
        onBlur={() => commit(text)}
        onSubmitEditing={() => commit(text)}
        style={[
          styles.input,
          {
            color: colors.text,
            borderColor: changed ? colors.accent : colors.border,
            backgroundColor: colors.surface,
          },
          lockedReason ? { opacity: 0.5 } : null,
        ]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 32 },
  labelBox: { width: 150 },
  input: {
    width: 64,
    borderWidth: 1,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.xs,
    paddingVertical: 2,
    fontSize: 14,
    textAlign: 'right',
  },
});
