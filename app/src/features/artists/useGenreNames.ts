import { useMemo } from 'react';

import { useListGenres } from '@/api/generated/reference/reference';

/** Genre slug → display name. The genre list is small and static, so it is cached for the session. */
export function useGenreNames(): ReadonlyMap<string, string> {
  const { data } = useListGenres({ query: { staleTime: Infinity } });
  return useMemo(() => new Map((data ?? []).map((g) => [g.slug, g.name])), [data]);
}
