/**
 * The console's date range, kept in the URL (`?from&to`, ISO instants) so a link or a reload shows the
 * same period. Presets are the last 24 hours, 7 days or 30 days, ending now. Anything the URL
 * holds that isn't a valid range falls back to the default preset instead of failing the page.
 */
export type RangePreset = '24h' | '7d' | '30d';

export type DateRange = {
  from: Date;
  to: Date;
  /** The preset the range matches, or null for a custom range. */
  preset: RangePreset | null;
};

export const RANGE_PRESETS: readonly { id: RangePreset; label: string; ms: number }[] = [
  { id: '24h', label: '24 h', ms: 24 * 60 * 60 * 1000 },
  { id: '7d', label: '7 d', ms: 7 * 24 * 60 * 60 * 1000 },
  { id: '30d', label: '30 d', ms: 30 * 24 * 60 * 60 * 1000 },
];

export const DEFAULT_PRESET: RangePreset = '7d';

/** The analytics endpoints refuse more than this, so the URL can't ask for it either. */
export const MAX_RANGE_DAYS = 90;

/** A preset's range ending at `now`. */
export function presetRange(preset: RangePreset, now: Date): DateRange {
  const ms = RANGE_PRESETS.find((p) => p.id === preset)?.ms ?? 0;
  return { from: new Date(now.getTime() - ms), to: now, preset };
}

/** One URL search parameter: expo-router gives a string, an array (repeated key) or nothing. */
type Param = string | string[] | undefined;

function single(value: Param): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function parseInstant(value: string | undefined): Date | null {
  // Full ISO instants only (what rangeParams writes): Date.parse accepts too much ("1", "2026").
  if (
    !value ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})$/.test(value)
  ) {
    return null;
  }
  const t = Date.parse(value);
  return Number.isNaN(t) ? null : new Date(t);
}

/**
 * The range in the URL. A missing, unparseable, reversed or over-long range gives the default preset.
 * `range=24h|7d|30d` (what the preset buttons write) is relative to `now`, so a preset link stays "the
 * last 7 days" when opened tomorrow; explicit `from`/`to` are a fixed period.
 */
export function parseRange(
  params: { from?: Param; to?: Param; range?: Param },
  now: Date,
): DateRange {
  const preset = single(params.range);
  if (preset === '24h' || preset === '7d' || preset === '30d') {
    return presetRange(preset, now);
  }
  const from = parseInstant(single(params.from));
  const to = parseInstant(single(params.to));
  if (!from || !to || from >= to) return presetRange(DEFAULT_PRESET, now);
  if (to.getTime() - from.getTime() > MAX_RANGE_DAYS * 24 * 60 * 60 * 1000) {
    return presetRange(DEFAULT_PRESET, now);
  }
  return { from, to, preset: null };
}

/** The URL parameters for a range: a preset stays relative; a custom range is written as instants. */
export function rangeParams(range: DateRange): { range?: string; from?: string; to?: string } {
  if (range.preset) return { range: range.preset, from: undefined, to: undefined };
  return { range: undefined, from: range.from.toISOString(), to: range.to.toISOString() };
}

/** What the API's `from`/`to` query parameters take. */
export function rangeQuery(range: DateRange): { from: string; to: string } {
  return { from: range.from.toISOString(), to: range.to.toISOString() };
}
