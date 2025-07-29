import { useCallback, useInsertionEffect, useRef } from 'react';

/**
 * React's experimental `useEffectEvent`, which React 19.0 doesn't export: a function an effect can call
 * (also later, from a promise) that always runs the latest render's `callback`, without the effect
 * depending on it. The callback is swapped in an insertion effect, so before any layout or passive effect
 * of the same commit runs. Unlike React's, calling it during rendering does not throw.
 */
export function useEffectEvent<Args extends unknown[], Result>(
  callback: (...args: Args) => Result,
): (...args: Args) => Result {
  const latest = useRef(callback);
  useInsertionEffect(() => {
    latest.current = callback;
  });
  return useCallback((...args: Args) => {
    const current = latest.current;
    return current(...args);
  }, []);
}
