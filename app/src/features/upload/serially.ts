/**
 * Wraps an async function so that calls run one after another, in the order they were made: each call
 * starts when the previous one has settled (resolved or rejected). Every caller still gets its own
 * result or error.
 */
export function serially<A extends unknown[], R>(fn: (...args: A) => Promise<R>) {
  let tail: Promise<unknown> = Promise.resolve();
  return (...args: A): Promise<R> => {
    const run = tail.then(() => fn(...args));
    tail = run.catch(() => undefined);
    return run;
  };
}
