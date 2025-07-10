import { useEffect, useState } from 'react';
import { AppState } from 'react-native';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** Past this age a time is shown as its date. */
const WEEK = 7 * DAY;

function dateParts(at: Date, timeZone: string | undefined) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone,
  }).formatToParts(at);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return { day: get('day'), month: get('month'), year: get('year') };
}

type Age =
  | { unit: 'now' }
  | { unit: 'm' | 'h' | 'd'; value: number }
  | { unit: 'date'; text: string };

function age(iso: string, now: number, timeZone: string | undefined): Age {
  const at = new Date(iso);
  // A time slightly ahead of the phone's clock (the server's clock) is "now", not "in 2m".
  const elapsed = Math.max(0, now - at.getTime());
  if (elapsed < MINUTE) return { unit: 'now' };
  if (elapsed < HOUR) return { unit: 'm', value: Math.floor(elapsed / MINUTE) };
  if (elapsed < DAY) return { unit: 'h', value: Math.floor(elapsed / HOUR) };
  if (elapsed < WEEK) return { unit: 'd', value: Math.floor(elapsed / DAY) };
  const then = dateParts(at, timeZone);
  const today = dateParts(new Date(now), timeZone);
  // Built from parts: engines punctuate the whole-string format differently (see formatGigDate).
  const text =
    then.year === today.year
      ? `${then.day} ${then.month}`
      : `${then.day} ${then.month} ${then.year}`;
  return { unit: 'date', text };
}

/**
 * How long ago something happened, as comments and the Activity inbox show it: "now", "9m", "3h",
 * "2d", then the date ("25 Sept", or "25 Sept 2025" in another year). `timeZone` is the device's own
 * unless given (tests pin it).
 */
export function relativeTime(iso: string, now: number, timeZone?: string): string {
  const a = age(iso, now, timeZone);
  switch (a.unit) {
    case 'now':
      return 'now';
    case 'date':
      return a.text;
    default:
      return `${a.value}${a.unit}`;
  }
}

/** The same for screen readers: "just now", "9 minutes ago", "1 hour ago", "on 25 Sept". */
export function relativeTimeLabel(iso: string, now: number, timeZone?: string): string {
  const a = age(iso, now, timeZone);
  switch (a.unit) {
    case 'now':
      return 'just now';
    case 'date':
      return `on ${a.text}`;
    default: {
      const noun = a.unit === 'm' ? 'minute' : a.unit === 'h' ? 'hour' : 'day';
      return `${a.value} ${noun}${a.value === 1 ? '' : 's'} ago`;
    }
  }
}

/**
 * The current time, read again every `everyMs` while the screen is mounted and whenever the app comes
 * back to the foreground, so "9m" turns into "10m" on a screen left open.
 */
export function useNow(everyMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const tick = () => setNow(Date.now());
    const timer = setInterval(tick, everyMs);
    const listener = AppState.addEventListener('change', (state) => {
      if (state === 'active') tick();
    });
    return () => {
      clearInterval(timer);
      listener.remove();
    };
  }, [everyMs]);
  return now;
}
