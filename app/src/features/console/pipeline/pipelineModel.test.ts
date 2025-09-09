import {
  attentionReason,
  barScale,
  dailyTranscodeChart,
  failChanges,
  failuresChart,
  formatMs,
  retryChanges,
  stageSegments,
  updatedAgo,
} from './pipelineModel';
import { asset, job } from './pipelineTestData';

const rule = { uploadedMinutes: 10, leaseMinutes: 15 };

describe('updatedAgo', () => {
  it('counts seconds, then minutes, and says nothing before the first answer', () => {
    expect(updatedAgo(0, 5000)).toBeNull();
    expect(updatedAgo(10_000, 11_500)).toBe('updated just now');
    expect(updatedAgo(10_000, 17_000)).toBe('updated 7 s ago');
    expect(updatedAgo(10_000, 10_000 + 125_000)).toBe('updated 2 min ago');
  });
});

describe('formatMs', () => {
  it('picks a readable unit', () => {
    expect(formatMs(null)).toBe('—');
    expect(formatMs(840)).toBe('840 ms');
    expect(formatMs(12_345)).toBe('12.3 s');
    expect(formatMs(95_000)).toBe('1 min 35 s');
  });
});

describe('stage bars', () => {
  it('draws each timed stage as a share of the longest row', () => {
    const long = job(); // 1000 + 500 + 7000 + 1500 = 10 000 ms
    const short = job({
      id: 'job-2',
      downloadMs: 500,
      probeMs: 500,
      transcodeMs: 3000,
      uploadMs: 1000,
    });
    const scale = barScale([long, short]);
    expect(scale).toBe(10_000);
    expect(stageSegments(long, scale)).toEqual([
      { stage: 'download', ms: 1000, percent: 10 },
      { stage: 'probe', ms: 500, percent: 5 },
      { stage: 'transcode', ms: 7000, percent: 70 },
      { stage: 'upload', ms: 1500, percent: 15 },
    ]);
    expect(stageSegments(short, scale).map((s) => s.percent)).toEqual([5, 5, 30, 10]);
  });

  it('leaves out stages a failed attempt never reached, and never exceeds 100 %', () => {
    const failed = job({
      status: 'FAILED',
      transcodeMs: null,
      uploadMs: null,
      downloadMs: 333,
      probeMs: 667,
    });
    const segments = stageSegments(failed, barScale([failed]));
    expect(segments.map((s) => s.stage)).toEqual(['download', 'probe']);
    expect(segments.reduce((sum, s) => sum + s.percent, 0)).toBeLessThanOrEqual(100 + 1e-9);
    expect(
      stageSegments(job({ downloadMs: null, probeMs: null, transcodeMs: null, uploadMs: null }), 0),
    ).toEqual([]);
  });
});

describe('confirm texts', () => {
  it('Retry says the clip goes back in line with fresh attempts and the post comes back', () => {
    const lines = retryChanges(asset());
    expect(lines[0]).toBe(
      'Clip 87654321 goes from FAILED back to UPLOADED, with a fresh set of 3 attempts',
    );
    expect(lines.join('\n')).toContain('post that failed because of this clip waits for it again');
    expect(lines.join('\n')).toContain('Deleted or hidden posts are not touched');
  });

  it('Fail says what happens to a stranded clip and its post', () => {
    const processing = asset({ status: 'PROCESSING', stranded: true, reason: null });
    expect(failChanges(processing, rule)).toEqual([
      'Clip 87654321 goes from PROCESSING to FAILED (reason OPERATOR)',
      'Its abandoned attempt (no heartbeat for over 15 min) is closed',
      'Its post fails if no other clip in it can still play, and the author is told',
      'You can still Retry it afterwards',
    ]);
    expect(failChanges(asset({ status: 'UPLOADED', stranded: true }), rule)[1]).toBe(
      'It has waited over 10 min with nothing working on it',
    );
  });

  it('explains why a clip is on the list', () => {
    expect(attentionReason(asset(), rule)).toBe('ffmpeg exited with 1');
    expect(attentionReason(asset({ status: 'UPLOADED', stranded: true }), rule)).toBe(
      'Stranded: waiting over 10 min with no job',
    );
    expect(attentionReason(asset({ status: 'PROCESSING', stranded: true }), rule)).toBe(
      'Stranded: no heartbeat for over 15 min',
    );
  });
});

describe('charts', () => {
  it('turns failures and daily percentiles into chart specs', () => {
    expect(failuresChart([{ reason: 'ffmpeg exited with 1', count: 3 }])).toEqual({
      kind: 'bar',
      categories: ['ffmpeg exited with 1'],
      series: [{ key: 'count', label: 'Failed attempts', values: [3] }],
    });
    const daily = dailyTranscodeChart([
      { day: '2026-10-04', count: 5, p50Ms: 6540, p95Ms: 12_049 },
    ]);
    expect(daily.x).toEqual(['2026-10-04']);
    expect(daily.series.map((s) => s.values)).toEqual([[6.5], [12]]);
    expect(daily.unit).toBe('s');
  });
});
