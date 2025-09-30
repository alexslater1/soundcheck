import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import {
  adminListRecentGigEdits,
  getAdminListGigEditsQueryKey,
  getAdminListRecentGigEditsQueryKey,
  useAdminListGigEdits,
  useAdminUndoGigEdit,
} from '@/api/generated/admin-catalog/admin-catalog';
import type { GigEdit, GigSummary, RecentGigEdit } from '@/api/generated/model';
import { formatGigDate } from '@/features/gigs/format';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { ConfirmDialog } from '../ConfirmDialog';
import { DataTable, type Column } from '../DataTable';
import { problemLines } from '../ranking/configModel';
import { editSummary, undoChanges } from './editModel';
import { GigSearchPicker } from './GigSearchPicker';
import { gigLine } from './mergeModel';
import { useHandles, who } from './useHandles';

/**
 * The Edit history tab: every gig's edits, newest first, until a gig is picked; then that gig's.
 * Undo one from either list. An undo puts the old value back and is itself logged; the server refuses it when
 * a later edit changed the same thing, or when an act it would remove is on clips.
 */
export function EditHistoryTab() {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [gig, setGig] = useState<GigSummary | null>(null);
  // The edit to undo, with the gig it is on ("Keo · Sat 3 Oct 2026 …") for the confirmation.
  const [undoing, setUndoing] = useState<{ edit: GigEdit; on: string } | null>(null);
  const edits = useAdminListGigEdits(gig?.id ?? '', { query: { enabled: gig !== null } });
  const recent = useInfiniteQuery({
    queryKey: [...getAdminListRecentGigEditsQueryKey(), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      adminListRecentGigEdits(pageParam ? { cursor: pageParam } : {}, { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: gig === null,
  });
  const recentRows = recent.data?.pages.flatMap((p) => p.items);
  const handles = useHandles(
    gig ? (edits.data ?? []).map((e) => e.userId) : (recentRows ?? []).map((r) => r.edit.userId),
  );
  const undo = useAdminUndoGigEdit({
    mutation: {
      onSuccess: () => {
        setUndoing(null);
        void client.invalidateQueries({ queryKey: getAdminListRecentGigEditsQueryKey() });
        return client.invalidateQueries({ queryKey: getAdminListGigEditsQueryKey(gig?.id ?? '') });
      },
    },
  });
  const startUndo = (edit: GigEdit, on: string) => {
    undo.reset();
    setUndoing({ edit, on });
  };

  const columns: Column<GigEdit>[] = [
    {
      key: 'when',
      title: 'When',
      flex: 1.2,
      sortValue: (e) => e.createdAt,
      render: (e) => (
        <Text variant="caption" muted>
          {e.createdAt.slice(0, 16).replace('T', ' ')}
        </Text>
      ),
    },
    {
      key: 'who',
      title: 'Who',
      render: (e) => <Text variant="caption">{who(handles, e.userId)}</Text>,
    },
    {
      key: 'change',
      title: 'Change',
      flex: 4,
      render: (e) => (
        <View>
          <Text>{editSummary(e)}</Text>
          {e.undoesEditId ? (
            <Text variant="caption" muted>
              Undo of edit {e.undoesEditId.slice(0, 8)}
            </Text>
          ) : null}
          {e.undoneByEditId ? (
            <Text variant="caption" style={{ color: colors.warning }}>
              Undone by edit {e.undoneByEditId.slice(0, 8)}
            </Text>
          ) : null}
        </View>
      ),
    },
    {
      key: 'actions',
      title: '',
      render: (e) =>
        e.undoable ? (
          <Button
            title="Undo…"
            size="small"
            variant="secondary"
            onPress={() => startUndo(e, gig ? gigLine(gig) : '')}
            testID={`edit-undo-${e.id}`}
          />
        ) : (
          <Text variant="caption" muted>
            Can&apos;t be undone
          </Text>
        ),
    },
  ];

  return (
    <View style={styles.page} testID="catalog-edits">
      <GigSearchPicker
        label="Gig"
        value={gig}
        onChange={(g) => {
          setGig(g);
          undo.reset();
        }}
        testID="edits-pick-gig"
      />
      {gig === null ? (
        <DataTable
          testID="edits-recent"
          columns={recentColumns(columns, startUndo)}
          rows={recentRows}
          rowKey={(r) => r.edit.id}
          status={recent.status}
          onRetry={() => void recent.refetch()}
          emptyTitle="No edits yet"
          emptyDetail="Edits fans make to gigs appear here, newest first. Pick a gig to see only its own."
          hasMore={recent.hasNextPage}
          loadingMore={recent.isFetchingNextPage}
          onLoadMore={() => void recent.fetchNextPage()}
        />
      ) : (
        <DataTable
          testID="edits-table"
          columns={columns}
          rows={edits.data}
          rowKey={(e) => e.id}
          status={edits.status}
          onRetry={() => void edits.refetch()}
          emptyTitle="No edits"
          emptyDetail="Nobody has changed this gig since it was listed or added."
        />
      )}
      {undoing ? (
        <ConfirmDialog
          visible
          title="Undo this edit?"
          changes={[`On ${undoing.on}:`, ...undoChanges(undoing.edit)]}
          confirmLabel="Undo"
          destructive
          busy={undo.isPending}
          error={undo.isError ? problemLines(undo.error).join(' · ') : null}
          onConfirm={() => undo.mutate({ id: undoing.edit.id })}
          onCancel={() => setUndoing(null)}
        />
      ) : null}
    </View>
  );
}

/** "Keo · Sat 3 Oct 2026": the gig a recent edit is on. */
export function recentGigLine(r: RecentGigEdit): string {
  return `${r.gigTitle} · ${formatGigDate(r.gigStartsAt)}`;
}

/**
 * The single gig's columns, read through each row's edit, after a Gig column; Undo names the gig the
 * edit is on.
 */
function recentColumns(
  columns: Column<GigEdit>[],
  startUndo: (edit: GigEdit, on: string) => void,
): Column<RecentGigEdit>[] {
  const gigColumn: Column<RecentGigEdit> = {
    key: 'gig',
    title: 'Gig',
    flex: 2.5,
    sortValue: (r) => r.gigTitle,
    render: (r) => <Text>{recentGigLine(r)}</Text>,
  };
  return [
    gigColumn,
    ...columns.map((c): Column<RecentGigEdit> => {
      const sortValue = c.sortValue;
      return {
        ...c,
        sortValue: sortValue ? (r) => sortValue(r.edit) : undefined,
        render:
          c.key === 'actions'
            ? (r) =>
                r.edit.undoable ? (
                  <Button
                    title="Undo…"
                    size="small"
                    variant="secondary"
                    onPress={() => startUndo(r.edit, recentGigLine(r))}
                    testID={`edit-undo-${r.edit.id}`}
                  />
                ) : (
                  c.render(r.edit)
                )
            : (r) => c.render(r.edit),
      };
    }),
  ];
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
});
