import { backoffMs, withRetries } from './retry';

const options = (sleeps: number[]) => ({
  retries: 3,
  baseMs: 500,
  sleep: async (ms: number) => {
    sleeps.push(ms);
  },
  random: () => 0.5,
});

describe('withRetries', () => {
  it('returns the first success without sleeping', async () => {
    const sleeps: number[] = [];
    await expect(withRetries(async () => 'ok', options(sleeps))).resolves.toBe('ok');
    expect(sleeps).toEqual([]);
  });

  it('tries again with a doubling delay plus jitter', async () => {
    const sleeps: number[] = [];
    let calls = 0;
    const result = await withRetries(async () => {
      calls += 1;
      if (calls < 4) throw new Error(`fail ${calls}`);
      return 'ok';
    }, options(sleeps));

    expect(result).toBe('ok');
    expect(calls).toBe(4);
    expect(sleeps).toEqual([750, 1250, 2250]); // 500, 1000, 2000 + 0.5 × 500
  });

  it('gives up after the retries and throws the last error', async () => {
    const sleeps: number[] = [];
    let calls = 0;
    await expect(
      withRetries(async () => {
        calls += 1;
        throw new Error(`fail ${calls}`);
      }, options(sleeps)),
    ).rejects.toThrow('fail 4');
    expect(calls).toBe(4); // the first try and three retries
    expect(sleeps).toHaveLength(3);
  });

  it('keeps the jitter within one base', () => {
    expect(backoffMs(1, 500, () => 0)).toBe(500);
    expect(backoffMs(1, 500, () => 0.999)).toBe(1000);
    expect(backoffMs(3, 500, () => 0)).toBe(2000);
  });
});
