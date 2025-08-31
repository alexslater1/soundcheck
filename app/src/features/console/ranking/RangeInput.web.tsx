import type { CSSProperties } from 'react';

import type { RangeInputProps } from './rangeInputTypes';

/**
 * A slider on the web: the browser's own `<input type="range">` (keyboard, screen readers and touch for
 * free). The console only runs on the web; `RangeInput.tsx` is the native stand-in.
 */
export function RangeInput({
  value,
  min,
  max,
  step,
  label,
  accent,
  disabled = false,
  onChange,
  testID,
}: RangeInputProps) {
  const style: CSSProperties = { flex: 1, minWidth: 80, accentColor: accent };
  return (
    <input
      type="range"
      aria-label={label}
      data-testid={testID}
      min={min}
      max={max}
      step={step}
      value={Math.min(max, Math.max(min, value))}
      disabled={disabled}
      onChange={(e) => onChange(Number(e.target.value))}
      style={style}
    />
  );
}
