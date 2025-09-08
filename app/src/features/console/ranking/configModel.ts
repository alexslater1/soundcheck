import type { RankingConfigView, RankingParams } from '@/api/generated/model';

/**
 * The ranking playground's model: what the sliders edit, what the confirm dialog says changed,
 * and how a rank moved between two lists. Pure functions, so each is unit-tested.
 */

/** The part of a config the playground edits: a weight per feature plus `bias`, and the params. */
export type EditableConfig = { weights: Record<string, number>; params: RankingParams };

/** The weight that stays 0: the feature is 0 at serve time, and only the lab uses it. */
export const LOCKED_WEIGHT = 'position_prior';
export const LOCKED_REASON =
  'Locked at 0: position_prior is always 0 when the feed is served; only the lab uses it, to soak up position bias in training.';

/** A slider's range. Weights: −5…5 on the slider; the box accepts what the server does (|w| ≤ 10). */
export type Range = { min: number; max: number; step: number; inputMin: number; inputMax: number };

export const WEIGHT_RANGE: Range = { min: -5, max: 5, step: 0.1, inputMin: -10, inputMax: 10 };

/** The params the playground shows, with the server's limits. */
export const PARAM_SLIDERS: readonly { key: keyof RankingParams; label: string; range: Range }[] = [
  {
    key: 'halfLifeHours',
    label: 'Recency half-life (h)',
    range: { min: 1, max: 168, step: 1, inputMin: 0.1, inputMax: 720 },
  },
  {
    key: 'priorStrength',
    label: 'Prior strength',
    range: { min: 1, max: 200, step: 1, inputMin: 0.1, inputMax: 10_000 },
  },
  {
    key: 'diversityLambda',
    label: 'Diversity λ',
    range: { min: 0, max: 1, step: 0.01, inputMin: 0, inputMax: 1 },
  },
  {
    key: 'explorationRate',
    label: 'Exploration ε',
    range: { min: 0, max: 0.3, step: 0.01, inputMin: 0, inputMax: 0.3 },
  },
  {
    key: 'authorCapPerSession',
    label: 'Posts per author per session',
    range: { min: 1, max: 50, step: 1, inputMin: 1, inputMax: 50 },
  },
];

/** The order weights are shown in: bias first, then the features as the config lists them. */
export function weightKeys(weights: Record<string, number>): string[] {
  return ['bias', ...Object.keys(weights).filter((k) => k !== 'bias')];
}

export function editable(config: RankingConfigView): EditableConfig {
  return { weights: { ...config.weights }, params: { ...config.params } };
}

/** Rounds to the slider's step, so 0.30000000000000004 reads 0.3 and compares equal. */
export function snap(value: number, step: number): number {
  const decimals = Math.max(0, Math.ceil(-Math.log10(step)));
  return Number(value.toFixed(decimals));
}

/** Keeps a typed number inside the range; NaN (an empty or half-typed box) gives null: no change. */
export function clampInput(text: string, range: Range): number | null {
  const value = Number(text.replace(',', '.'));
  if (text.trim() === '' || !Number.isFinite(value)) return null;
  return Math.min(range.inputMax, Math.max(range.inputMin, value));
}

function format(value: unknown): string {
  if (typeof value === 'number') return String(snap(value, 0.0001));
  if (value && typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/**
 * What changes from `from` to `to`, one line per changed weight or param ("recency: 1.5 → 0"), weights
 * first. Candidate caps are compared one source at a time. Empty when nothing changes.
 */
export function configDiff(from: EditableConfig, to: EditableConfig): string[] {
  const lines: string[] = [];
  const weightNames = [...new Set([...weightKeys(from.weights), ...weightKeys(to.weights)])];
  for (const name of weightNames) {
    const a = from.weights[name];
    const b = to.weights[name];
    if (a !== b) lines.push(`weight ${name}: ${format(a)} → ${format(b)}`);
  }
  const paramNames = [...new Set([...Object.keys(from.params), ...Object.keys(to.params)])].sort();
  for (const name of paramNames) {
    const a = (from.params as unknown as Record<string, unknown>)[name];
    const b = (to.params as unknown as Record<string, unknown>)[name];
    if (name === 'candidateCaps' && a && b && typeof a === 'object' && typeof b === 'object') {
      const caps = [...new Set([...Object.keys(a), ...Object.keys(b)])].sort();
      for (const source of caps) {
        const ca = (a as Record<string, unknown>)[source];
        const cb = (b as Record<string, unknown>)[source];
        if (ca !== cb) lines.push(`candidateCaps.${source}: ${format(ca)} → ${format(cb)}`);
      }
    } else if (format(a) !== format(b)) {
      lines.push(`${name}: ${format(a)} → ${format(b)}`);
    }
  }
  return lines;
}

export type RankChange =
  | { kind: 'up'; by: number; label: string }
  | { kind: 'down'; by: number; label: string }
  | { kind: 'same'; by: 0; label: string }
  | { kind: 'new'; by: null; label: string };

/**
 * How an item's rank in this list compares with its rank in the other (1 is the top): ▲3 when it is 3
 * places higher here, ▼2 when lower, = when equal, NEW when the other list doesn't show it.
 */
export function rankChange(rank: number, otherRank: number | null | undefined): RankChange {
  if (otherRank === null || otherRank === undefined) return { kind: 'new', by: null, label: 'NEW' };
  const by = otherRank - rank;
  if (by > 0) return { kind: 'up', by, label: `▲${by}` };
  if (by < 0) return { kind: 'down', by: -by, label: `▼${-by}` };
  return { kind: 'same', by: 0, label: '=' };
}

/** Configs grouped for the selector, each group newest first (the API's order). */
export function groupConfigs(configs: readonly RankingConfigView[]): {
  active: RankingConfigView | undefined;
  drafts: RankingConfigView[];
  archived: RankingConfigView[];
} {
  return {
    active: configs.find((c) => c.status === 'ACTIVE'),
    drafts: configs.filter((c) => c.status === 'DRAFT'),
    archived: configs.filter((c) => c.status === 'ARCHIVED'),
  };
}

/** The config "Re-activate previous" goes back to: the archived one that was active most recently. */
export function previousConfig(
  configs: readonly RankingConfigView[],
): RankingConfigView | undefined {
  return configs
    .filter((c) => c.status === 'ARCHIVED' && c.activatedAt)
    .sort((a, b) => (b.activatedAt ?? '').localeCompare(a.activatedAt ?? ''))[0];
}

/** A LAB draft's headline metrics and report path, for the selector ("AUC 0.71 · lab/reports/…"). */
export function labSummary(config: RankingConfigView): string | null {
  if (config.source !== 'LAB' || !config.metrics) return null;
  const m = config.metrics;
  const parts: string[] = [];
  // The lab says what it trained on: simulated data is always called simulated.
  if (m.data === 'simulated') parts.push('Trained on simulated data');
  for (const key of ['auc', 'logLoss', 'ndcg10', 'ndcgAt10']) {
    const v = m[key];
    if (typeof v === 'number') parts.push(`${key} ${snap(v, 0.001)}`);
  }
  const report = m.report ?? m.reportPath;
  if (typeof report === 'string') parts.push(report);
  return parts.length ? parts.join(' · ') : null;
}

/** σ(x), as the server's scorer computes it. */
export function sigmoid(x: number): number {
  return x >= 0 ? 1 / (1 + Math.exp(-x)) : Math.exp(x) / (1 + Math.exp(x));
}

/** Replay refuses windows over 14 days. */
export const MAX_REPLAY_DAYS = 14;

/**
 * The replay window for the console's date range: the range itself, or its last 14 days when it is
 * longer (`clamped` says so, and the panel tells the operator).
 */
export function replayWindow(range: { from: Date; to: Date }): {
  from: Date;
  to: Date;
  clamped: boolean;
} {
  const max = MAX_REPLAY_DAYS * 24 * 60 * 60 * 1000;
  if (range.to.getTime() - range.from.getTime() <= max) {
    return { from: range.from, to: range.to, clamped: false };
  }
  return { from: new Date(range.to.getTime() - max), to: range.to, clamped: true };
}

/** A metric for the replay table: three decimals, or "—" when it is undefined for the window. */
export function metric(value: number | null | undefined, digits = 3): string {
  return value === null || value === undefined ? '—' : value.toFixed(digits);
}

/** What went wrong, one line each: every broken rule of a refused config, or the error's message. */
export function problemLines(error: unknown): string[] {
  if (error && typeof error === 'object' && 'problem' in error) {
    const problem = (error as { problem?: { errors?: { message: string }[]; detail?: string } })
      .problem;
    if (problem?.errors?.length) return problem.errors.map((e) => e.message);
    if (problem?.detail) return [problem.detail];
  }
  return [error instanceof Error ? error.message : 'Something went wrong'];
}
