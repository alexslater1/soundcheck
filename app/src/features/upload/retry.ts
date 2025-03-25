export type RetryOptions = {
  /** Extra tries after the first one fails. */
  retries: number;
  baseMs: number;
  sleep: (ms: number) => Promise<void>;
  /** 0 ≤ value < 1; injected so tests are deterministic. */
  random: () => number;
};

/** Delay before retry number `attempt` (1-based): the base doubled each time, plus up to one base of jitter. */
export function backoffMs(attempt: number, baseMs: number, random: () => number): number {
  return Math.round(baseMs * 2 ** (attempt - 1) + random() * baseMs);
}

/**
 * Runs `task`, trying again after a failure up to `retries` times with exponential backoff and jitter
 * (jitter keeps four parts that failed together from retrying at the same instant). The last error is
 * thrown when every try has failed.
 */
export async function withRetries<T>(task: () => Promise<T>, options: RetryOptions): Promise<T> {
  let attempt = 0;
  for (;;) {
    try {
      return await task();
    } catch (error) {
      attempt += 1;
      if (attempt > options.retries) {
        throw error;
      }
      await options.sleep(backoffMs(attempt, options.baseMs, options.random));
    }
  }
}
