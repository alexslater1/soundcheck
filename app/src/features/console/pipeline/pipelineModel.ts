import type {
  PipelineAsset,
  PipelineDay,
  PipelineFailureReason,
  PipelineJob,
  PipelineStrandedRule,
} from '@/api/generated/model';

import type { BarChartSpec, LineChartSpec } from '../charts/chartTypes';

/** How often the page asks the server again (react-query pauses it while the tab is hidden). */
export const POLL_MS = 5000;

/** The stages a recent-jobs row draws, in pipeline order (queue wait is shown as its own column). */
export const BAR_STAGES = ['download', 'probe', 'transcode', 'upload'] as const;
export type BarStage = (typeof BAR_STAGES)[number];

export const STAGE_LABELS: Record<string, string> = {
  queueWait: 'Queue wait',
  download: 'Download',
  probe: 'Probe',
  transcode: 'Transcode',
  upload: 'Upload',
  total: 'Total',
};

/** "updated just now", "updated 7 s ago", "updated 2 min ago"; null before the first answer. */
export function updatedAgo(updatedAtMs: number, nowMs: number): string | null {
  if (!updatedAtMs) return null;
  const s = Math.max(0, Math.floor((nowMs - updatedAtMs) / 1000));
  if (s < 2) return 'updated just now';
  if (s < 60) return `updated ${s} s ago`;
  return `updated ${Math.floor(s / 60)} min ago`;
}

/** 840 → "840 ms", 12 345 → "12.3 s", 95 000 → "1 min 35 s"; null → "—". */
export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const total = Math.round(ms / 1000);
  return `${Math.floor(total / 60)} min ${total % 60} s`;
}

function stageMs(job: PipelineJob, stage: BarStage): number {
  const value =
    stage === 'download'
      ? job.downloadMs
      : stage === 'probe'
        ? job.probeMs
        : stage === 'transcode'
          ? job.transcodeMs
          : job.uploadMs;
  return typeof value === 'number' && value > 0 ? value : 0;
}

/** The sum of a job's timed stages (what its bar is long). */
export function stagesTotal(job: PipelineJob): number {
  return BAR_STAGES.reduce((sum, stage) => sum + stageMs(job, stage), 0);
}

export type BarSegment = { stage: BarStage; ms: number; percent: number };

/**
 * One row's stacked bar: each timed stage as a share of `scaleMs` (the longest row in the table), so
 * rows compare at a glance. A stage the attempt never reached has no segment; percentages are rounded
 * to one decimal and never exceed 100 in total.
 */
export function stageSegments(job: PipelineJob, scaleMs: number): BarSegment[] {
  if (scaleMs <= 0) return [];
  return BAR_STAGES.map((stage) => ({ stage, ms: stageMs(job, stage) }))
    .filter((s) => s.ms > 0)
    .map((s) => ({ ...s, percent: Math.floor((s.ms / scaleMs) * 1000) / 10 }));
}

/** The scale for the table's bars: its longest row. */
export function barScale(jobs: readonly PipelineJob[]): number {
  return jobs.reduce((max, job) => Math.max(max, stagesTotal(job)), 0);
}

/** What Retry will change, one line each (the ConfirmDialog's list). */
export function retryChanges(asset: PipelineAsset): string[] {
  return [
    `Clip ${shortId(asset.id)} goes from ${asset.status} back to UPLOADED, with a fresh set of 3 attempts`,
    'A new transcode job is queued (a copy left in the queue or the DLQ is ignored by the worker)',
    'A post that failed because of this clip waits for it again, and is published when it is ready',
    'Deleted or hidden posts are not touched',
  ];
}

/** What Fail will change, one line each. */
export function failChanges(asset: PipelineAsset, rule: PipelineStrandedRule): string[] {
  return [
    `Clip ${shortId(asset.id)} goes from ${asset.status} to FAILED (reason OPERATOR)`,
    asset.status === 'PROCESSING'
      ? `Its abandoned attempt (no heartbeat for over ${rule.leaseMinutes} min) is closed`
      : `It has waited over ${rule.uploadedMinutes} min with nothing working on it`,
    'Its post fails if no other clip in it can still play, and the author is told',
    'You can still Retry it afterwards',
  ];
}

/** Why an asset is on the list, in words. */
export function attentionReason(asset: PipelineAsset, rule: PipelineStrandedRule): string {
  if (!asset.stranded) return asset.reason ?? 'Failed';
  return asset.status === 'PROCESSING'
    ? `Stranded: no heartbeat for over ${rule.leaseMinutes} min`
    : `Stranded: waiting over ${rule.uploadedMinutes} min with no job`;
}

export function shortId(id: string): string {
  return id.slice(-8);
}

/** Failures by reason as a bar chart (most frequent first, as the server sends them). */
export function failuresChart(reasons: readonly PipelineFailureReason[]): BarChartSpec {
  return {
    kind: 'bar',
    categories: reasons.map((r) => r.reason),
    series: [{ key: 'count', label: 'Failed attempts', values: reasons.map((r) => r.count) }],
  };
}

/** The transcode stage's daily p50 and p95, in seconds. */
export function dailyTranscodeChart(days: readonly PipelineDay[]): LineChartSpec {
  const seconds = (ms: number) => Math.round(ms / 100) / 10;
  return {
    kind: 'line',
    x: days.map((d) => d.day),
    series: [
      { key: 'p50', label: 'p50', values: days.map((d) => seconds(d.p50Ms)) },
      { key: 'p95', label: 'p95', values: days.map((d) => seconds(d.p95Ms)) },
    ],
    unit: 's',
  };
}
