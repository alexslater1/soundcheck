import type { RankingConfigView, RankingParams } from '@/api/generated/model';

import {
  clampInput,
  configDiff,
  labSummary,
  metric,
  previousConfig,
  problemLines,
  rankChange,
  replayWindow,
  snap,
  WEIGHT_RANGE,
  weightKeys,
  type EditableConfig,
} from './configModel';

const params: RankingParams = {
  halfLifeHours: 24,
  priorStrength: 20,
  diversityLambda: 0.8,
  explorationRate: 0.1,
  sessionSize: 200,
  pageSize: 10,
  candidateCaps: { FOLLOWING: 300, FRESH: 100 },
  localRadiusKm: 50,
  seenWindowDays: 3,
  authorCapPerSession: 5,
};

const base: EditableConfig = {
  weights: { bias: -2, recency: 1.5, seen: -2.5 },
  params,
};

function config(overrides: Partial<RankingConfigView>): RankingConfigView {
  return {
    id: 'c',
    version: 1,
    status: 'DRAFT',
    source: 'MANUAL',
    featureSchemaVersion: 1,
    weights: base.weights,
    params,
    createdAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

describe('configDiff', () => {
  it('says nothing when nothing changes', () => {
    expect(configDiff(base, { weights: { ...base.weights }, params: { ...params } })).toEqual([]);
  });

  it('lists each changed weight, then each changed param', () => {
    const to: EditableConfig = {
      weights: { ...base.weights, recency: 0 },
      params: { ...params, halfLifeHours: 12, authorCapPerSession: 3 },
    };
    expect(configDiff(base, to)).toEqual([
      'weight recency: 1.5 → 0',
      'authorCapPerSession: 5 → 3',
      'halfLifeHours: 24 → 12',
    ]);
  });

  it('compares candidate caps one source at a time', () => {
    const to: EditableConfig = {
      weights: base.weights,
      params: { ...params, candidateCaps: { FOLLOWING: 300, FRESH: 50 } },
    };
    expect(configDiff(base, to)).toEqual(['candidateCaps.FRESH: 100 → 50']);
  });

  it('reads floating-point noise as no change', () => {
    const to: EditableConfig = {
      weights: { ...base.weights },
      params: { ...params, diversityLambda: 0.1 + 0.7 },
    };
    expect(configDiff(base, to)).toEqual([]);
  });
});

describe('rankChange', () => {
  it('shows a rise, a fall, no move and a newcomer', () => {
    expect(rankChange(2, 5)).toEqual({ kind: 'up', by: 3, label: '▲3' });
    expect(rankChange(7, 5)).toEqual({ kind: 'down', by: 2, label: '▼2' });
    expect(rankChange(4, 4)).toEqual({ kind: 'same', by: 0, label: '=' });
    expect(rankChange(1, null)).toEqual({ kind: 'new', by: null, label: 'NEW' });
    expect(rankChange(1, undefined).label).toBe('NEW');
  });
});

describe('the sliders', () => {
  it('puts bias first', () => {
    expect(weightKeys({ recency: 1, bias: -2, seen: -1 })).toEqual(['bias', 'recency', 'seen']);
  });

  it('snaps to the step and clamps what is typed to what the server accepts', () => {
    expect(snap(0.1 + 0.2, 0.1)).toBe(0.3);
    expect(clampInput('12', WEIGHT_RANGE)).toBe(10);
    expect(clampInput('-3,5', WEIGHT_RANGE)).toBe(-3.5);
    expect(clampInput('', WEIGHT_RANGE)).toBeNull();
    expect(clampInput('-', WEIGHT_RANGE)).toBeNull();
  });
});

describe('the config list', () => {
  it('goes back to the archived config that was active most recently', () => {
    const configs = [
      config({ id: 'draft', status: 'DRAFT' }),
      config({ id: 'now', status: 'ACTIVE', activatedAt: '2026-10-04T10:00:00Z' }),
      config({ id: 'older', status: 'ARCHIVED', activatedAt: '2026-10-01T10:00:00Z' }),
      config({ id: 'last', status: 'ARCHIVED', activatedAt: '2026-10-03T10:00:00Z' }),
      config({ id: 'never', status: 'ARCHIVED', activatedAt: null }),
    ];
    expect(previousConfig(configs)?.id).toBe('last');
    expect(previousConfig([config({ status: 'ACTIVE' })])).toBeUndefined();
  });

  it("sums up a lab draft's metrics and report", () => {
    expect(
      labSummary(
        config({ source: 'LAB', metrics: { auc: 0.71234, report: 'lab/reports/r1.html' } }),
      ),
    ).toBe('auc 0.712 · lab/reports/r1.html');
    expect(labSummary(config({ source: 'MANUAL', metrics: { auc: 0.7 } }))).toBeNull();
  });

  it('says a lab draft was trained on simulated data', () => {
    expect(
      labSummary(
        config({
          source: 'LAB',
          metrics: { data: 'simulated', auc: 0.7, ndcg10: 0.61234, report: 'lab/reports/x' },
        }),
      ),
    ).toBe('Trained on simulated data · auc 0.7 · ndcg10 0.612 · lab/reports/x');
  });
});

describe('replayWindow', () => {
  const to = new Date('2026-10-04T12:00:00Z');
  it('keeps a window of up to 14 days', () => {
    const from = new Date('2026-09-27T12:00:00Z');
    expect(replayWindow({ from, to })).toEqual({ from, to, clamped: false });
  });

  it('cuts a longer one to its last 14 days and says so', () => {
    const window = replayWindow({ from: new Date('2026-09-04T12:00:00Z'), to });
    expect(window.from.toISOString()).toBe('2026-09-20T12:00:00.000Z');
    expect(window.clamped).toBe(true);
  });

  it('shows an undefined metric as a dash', () => {
    expect(metric(null)).toBe('—');
    expect(metric(0.71234)).toBe('0.712');
  });
});

describe('problemLines', () => {
  it('lists every broken rule of a refused config', () => {
    const error = Object.assign(new Error('Request failed'), {
      problem: {
        status: 400,
        code: 'RANKING_CONFIG_INVALID',
        errors: [
          { field: 'config', message: 'diversityLambda must be between 0 and 1' },
          { field: 'config', message: 'authorCapPerSession must be between 1 and 50' },
        ],
      },
    });
    expect(problemLines(error)).toEqual([
      'diversityLambda must be between 0 and 1',
      'authorCapPerSession must be between 1 and 50',
    ]);
    expect(problemLines(new Error('offline'))).toEqual(['offline']);
  });
});
