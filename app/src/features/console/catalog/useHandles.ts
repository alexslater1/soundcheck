import { useAdminUsersByIds } from '@/api/generated/admin-users/admin-users';

/**
 * Handles for account ids (`GET /admin/users/by-ids`): catalog stores who added, reviewed or merged a gig
 * as an id only (it can't read identity), so the console names them here. Ids not yet resolved (or of an
 * account that is gone) are missing from the map; callers show a short id instead.
 */
export function useHandles(ids: readonly (string | null | undefined)[]): Record<string, string> {
  const unique = [...new Set(ids.filter((id): id is string => !!id))].sort().slice(0, 100);
  const query = useAdminUsersByIds({ ids: unique }, { query: { enabled: unique.length > 0 } });
  return Object.fromEntries((query.data ?? []).map((u) => [u.id, u.handle]));
}

/** "@sam", or the id's first eight characters while (or if) the handle is unknown. */
export function who(handles: Record<string, string>, id: string | null | undefined): string {
  if (!id) return '—';
  const handle = handles[id];
  return handle ? `@${handle}` : id.slice(0, 8);
}
