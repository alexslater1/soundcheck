import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useMemo } from 'react';

import { parseRange, rangeParams, type DateRange } from './dateRange';

/**
 * The console's date range from the URL, and a setter that writes it back. "Now" is taken when
 * the URL changes, not on every render, so a preset's query key stays put while the page is open.
 */
export function useConsoleRange(): [DateRange, (range: DateRange) => void] {
  const params = useLocalSearchParams<{ from?: string; to?: string; range?: string }>();
  const router = useRouter();
  const range = useMemo(
    () => parseRange({ from: params.from, to: params.to, range: params.range }, new Date()),
    [params.from, params.to, params.range],
  );
  const setRange = useCallback((next: DateRange) => router.setParams(rangeParams(next)), [router]);
  return [range, setRange];
}
