import { useQuery } from '@tanstack/react-query';

import {
  adminListIngestionRuns,
  getAdminListIngestionRunsQueryKey,
} from '@/api/generated/admin-ingestion/admin-ingestion';

import { isBreakage } from './issueKinds';

const LATEST_VENUE_RUN = { source: 'VENUE', limit: 1 } as const;

/**
 * The console nav's dot on Ingestion: true when the latest VENUE run met an empty or unreadable
 * venue site. One tiny query (one row), refreshed every few minutes; any failure just means no dot.
 */
export function useIngestionAlert(): boolean {
  const query = useQuery({
    queryKey: getAdminListIngestionRunsQueryKey(LATEST_VENUE_RUN),
    queryFn: ({ signal }) => adminListIngestionRuns(LATEST_VENUE_RUN, { signal }),
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
    retry: false,
  });
  return isBreakage(query.data?.items[0]);
}
