import { useQuery } from '@tanstack/react-query';

import { customFetch } from '@/api/http';

/** Shape of GET /api/v1/health. */
export type HealthResponse = { status: string; version: string; time: string };

export const healthQueryKey = ['health'] as const;

export function fetchHealth(): Promise<HealthResponse> {
  return customFetch<HealthResponse>('/api/v1/health');
}

export function useHealth() {
  return useQuery({ queryKey: healthQueryKey, queryFn: fetchHealth, refetchInterval: 15_000 });
}
