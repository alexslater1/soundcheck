/**
 * How the console overview writes its numbers. Pure functions, so each rule is tested once:
 * percentages, durations, counts, the change on the previous period, and the synthetic-share banner.
 * A missing value (no uploads finished, nothing served) is shown as an em dash, never as 0.
 */

export const NONE = '—';

/** 0.4 → "40 %", 0.0479 → "4.8 %" (one decimal under 10 %), null → "—". */
export function formatPercent(fraction: number | null | undefined): string {
  if (fraction === null || fraction === undefined || !Number.isFinite(fraction)) return NONE;
  const pct = fraction * 100;
  const shown = Math.abs(pct) < 10 ? Math.round(pct * 10) / 10 : Math.round(pct);
  return `${shown} %`;
}

/** 850 → "850 ms", 1400 → "1.4 s", 31102 → "31.1 s", 95655 → "1.6 min", null → "—". */
export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return NONE;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(Math.round(ms / 100) / 10).toFixed(1)} s`;
  return `${(Math.round(ms / 6000) / 10).toFixed(1)} min`;
}

/** 74 → "74", 10.857 → "10.9", 1234 → "1,234" (a whole number stays whole), null → "—". */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return NONE;
  const rounded = Number.isInteger(value) ? value : Math.round(value * 10) / 10;
  return rounded.toLocaleString('en-GB');
}

/**
 * The change on the previous period as a signed fraction (0.12 = 12 % up, −0.5 = halved), what
 * `KpiTile` shows as ▲/▼. Null when there is nothing to compare: either value missing, or a previous
 * value of 0 (any rise from nothing would be "infinite").
 */
export function signedDelta(
  value: number | null | undefined,
  previous: number | null | undefined,
): number | null {
  if (value === null || value === undefined || previous === null || previous === undefined) {
    return null;
  }
  if (previous === 0) return null;
  return (value - previous) / previous;
}

/** "2026-10-02" (a UK day from the API) → "2 Oct". */
export function dayLabel(day: string): string {
  const [y, m, d] = day.split('-').map(Number);
  if (!y || !m || !d) return day;
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  });
}

/** A ranking config as a chart category: "v3 · 2 Oct" (activated on that UK day), or "v3" if never. */
export function configLabel(version: number, activatedAt: string | null | undefined): string {
  if (!activatedAt) return `v${version}`;
  const date = new Date(activatedAt).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'Europe/London',
  });
  return `v${version} · ${date}`;
}

/**
 * The banner above the KPIs (synthetic data is called synthetic). Always shown when there was any
 * activity, even at 0 %, so its absence never reads as "all real".
 */
export function syntheticBanner(share: number | null | undefined): string {
  if (share === null || share === undefined) {
    return 'No activity in this period, so no share from seeded or simulated accounts to report.';
  }
  return `${formatPercent(share)} of this period's activity is from seeded or simulated accounts`;
}
