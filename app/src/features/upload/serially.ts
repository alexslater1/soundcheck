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

/**
 * A gate: steps passed to the returned function run one after another, in call order, each resolving
 * with its own result (or rejecting with its own error). Unlike `serially`, the steps can differ.
 */
export function oneAtATime(): <T>(step: () => Promise<T>) => Promise<T> {
  let tail: Promise<unknown> = Promise.resolve();
  return <T>(step: () => Promise<T>): Promise<T> => {
    const run = tail.then(step);
    tail = run.catch(() => undefined);
    return run;
  };
}
