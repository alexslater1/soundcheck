import { useEffect, useRef } from 'react';

import { pageStep } from './pagerKeys';

function inTextField(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
}

/**
 * ↓ / j / Page Down for the next post, ↑ / k / Page Up for the previous one, while the feed's screen is
 * the one in view. The browser's own scrolling for those keys
 * is cancelled, so one press is exactly one post.
 */
export function usePagerKeys(enabled: boolean, onStep: (step: 1 | -1) => void): void {
  const latest = useRef(onStep);
  useEffect(() => {
    latest.current = onStep;
  }, [onStep]);
  useEffect(() => {
    if (!enabled) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      const step = pageStep({
        key: event.key,
        altKey: event.altKey,
        ctrlKey: event.ctrlKey,
        metaKey: event.metaKey,
        inTextField: inTextField(event.target),
      });
      if (step === null) return;
      event.preventDefault();
      latest.current(step);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled]);
}
