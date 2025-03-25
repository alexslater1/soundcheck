import { isSettled, nextPollDelay, POLL_GIVE_UP_MS } from './mediaPolling';

describe('nextPollDelay', () => {
  it('backs off 1 s, 2 s, 4 s, 8 s and then stays at 10 s', () => {
    const delays = [1, 2, 3, 4, 5, 6, 7].map((polls) => nextPollDelay('PROCESSING', polls));
    expect(delays).toEqual([1000, 2000, 4000, 8000, 10_000, 10_000, 10_000]);
  });

  it('polls while the status is unknown (first request still in flight)', () => {
    expect(nextPollDelay(undefined, 0)).toBe(1000);
  });

  it.each(['READY', 'REJECTED', 'FAILED', 'DELETED'] as const)('stops at %s', (status) => {
    expect(nextPollDelay(status, 3)).toBe(false);
    expect(isSettled(status)).toBe(true);
  });

  it.each(['AWAITING_UPLOAD', 'UPLOADED', 'PROCESSING'] as const)('keeps going at %s', (status) => {
    expect(nextPollDelay(status, 1)).toBe(1000);
    expect(isSettled(status)).toBe(false);
  });

  it('gives up once the schedule has waited five minutes', () => {
    // Replay the schedule: add up the delays until it says stop.
    let waited = 0;
    let polls = 1;
    for (;;) {
      const delay = nextPollDelay('PROCESSING', polls);
      if (delay === false) {
        break;
      }
      waited += delay;
      polls += 1;
    }
    expect(waited).toBeGreaterThanOrEqual(POLL_GIVE_UP_MS);
    expect(waited).toBeLessThan(POLL_GIVE_UP_MS + 10_000);
    expect(polls).toBe(34); // 1 + 2 + 4 + 8 s, then 29 × 10 s
  });
});
