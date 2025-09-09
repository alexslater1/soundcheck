import type { PipelineAsset, PipelineJob, PipelineOverview } from '@/api/generated/model';

/** Fixtures for the pipeline page's tests. */
export const STRANDED_ID = 'stranded-0000-0000-0000-00000000abcd';

export function job(over: Partial<PipelineJob> = {}): PipelineJob {
  return {
    id: 'job-1',
    mediaId: '0199aaaa-bbbb-7ccc-8ddd-eeee12345678',
    attempt: 1,
    workerId: 'w-1',
    status: 'SUCCEEDED',
    queueWaitMs: 120,
    downloadMs: 1000,
    probeMs: 500,
    transcodeMs: 7000,
    uploadMs: 1500,
    totalMs: 10_000,
    error: null,
    startedAt: '2026-10-05T01:00:00Z',
    finishedAt: '2026-10-05T01:00:10Z',
    ...over,
  } as PipelineJob;
}

export function asset(over: Partial<PipelineAsset> = {}): PipelineAsset {
  return {
    id: '0199aaaa-bbbb-7ccc-8ddd-ffff87654321',
    ownerId: 'owner-1',
    kind: 'VIDEO',
    status: 'FAILED',
    attempts: 3,
    reason: 'ffmpeg exited with 1',
    since: '2026-10-05T00:40:00Z',
    stranded: false,
    ...over,
  };
}

export function overview(over: Partial<PipelineOverview> = {}): PipelineOverview {
  return {
    from: '2026-09-28T01:00:00Z',
    to: '2026-10-05T01:00:00Z',
    generatedAt: '2026-10-05T01:00:00Z',
    queue: { visible: 4, inFlight: 1, delayed: 0 },
    dlq: { visible: 2 },
    queueError: null,
    strandedRule: { uploadedMinutes: 10, leaseMinutes: 15 },
    statuses: [
      { status: 'FAILED', count: 1 },
      { status: 'PROCESSING', count: 2 },
      { status: 'READY', count: 40 },
    ],
    attention: [
      asset(),
      asset({
        id: STRANDED_ID,
        status: 'PROCESSING',
        stranded: true,
        reason: null,
        attempts: 1,
      }),
    ],
    recentJobs: [
      job(),
      job({
        id: 'job-2',
        status: 'FAILED',
        transcodeMs: null,
        uploadMs: null,
        error: 'ffmpeg exited with 1: bad frame',
      }),
    ],
    stages: [
      { stage: 'queueWait', count: 2, p50Ms: 120, p95Ms: 300 },
      { stage: 'transcode', count: 1, p50Ms: 7000, p95Ms: 7000 },
    ],
    failuresByReason: [{ reason: 'ffmpeg exited with 1', count: 1 }],
    dailyTranscode: [{ day: '2026-10-04', count: 1, p50Ms: 7000, p95Ms: 7000 }],
    ...over,
  };
}
