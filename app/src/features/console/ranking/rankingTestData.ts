import type {
  Post,
  RankingConfigView,
  RankingParams,
  RankingPreviewItem,
} from '@/api/generated/model';

/** Fixtures for the playground's and the inspector's tests. */
export const params: RankingParams = {
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

export function config(overrides: Partial<RankingConfigView>): RankingConfigView {
  return {
    id: 'c1',
    version: 1,
    status: 'ACTIVE',
    source: 'SEED',
    featureSchemaVersion: 1,
    weights: { bias: -2, recency: 1.5, seen: -2.5, position_prior: 0 },
    params,
    createdAt: '2026-10-01T00:00:00Z',
    activatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

export function post(id: string, handle: string): Post {
  return {
    id,
    author: { id: `a-${handle}`, handle, displayName: handle },
    artists: [{ id: 'ar1', name: 'The Band', genres: [], isFollowing: false }],
    playback: { status: 'READY', thumbUrl: `http://media/${id}.webp` },
    clips: [],
  } as unknown as Post;
}

export function item(
  rank: number,
  otherRank: number | null,
  handle = `user${rank}`,
): RankingPreviewItem {
  return {
    rank,
    otherRank,
    post: post(`p${rank}-${handle}`, handle),
    score: 0.5 - rank / 100,
    logit: -0.5,
    bias: -2,
    contributions: [
      { feature: 'recency', value: 0.5, weight: 1.5, contribution: 0.75 },
      { feature: 'seen', value: 0.3, weight: -2.5, contribution: -0.75 },
      { feature: 'position_prior', value: 0, weight: 0, contribution: 0 },
    ],
    sources: ['FOLLOWING'],
    exploration: false,
  };
}
