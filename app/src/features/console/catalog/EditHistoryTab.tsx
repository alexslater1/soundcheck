import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import {
  getAdminListGigEditsQueryKey,
  useAdminListGigEdits,
  useAdminUndoGigEdit,
} from '@/api/generated/admin-catalog/admin-catalog';
import type { GigEdit, GigSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { StateMessage } from '@/components/ui/StateMessage';
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
 * The Edit history tab: pick a gig, read every edit fans (and operators) made to it, newest
 * first, and undo one. An undo puts the old value back and is itself logged; the server refuses it when
 * a later edit changed the same thing, or when an act it would remove is on clips.
 */
export function EditHistoryTab() {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [gig, setGig] = useState<GigSummary | null>(null);
  const [undoing, setUndoing] = useState<GigEdit | null>(null);
  const edits = useAdminListGigEdits(gig?.id ?? '', { query: { enabled: gig !== null } });
  const handles = useHandles((edits.data ?? []).map((e) => e.userId));
  const undo = useAdminUndoGigEdit({
    mutation: {
      onSuccess: () => {
        setUndoing(null);
        return client.invalidateQueries({ queryKey: getAdminListGigEditsQueryKey(gig?.id ?? '') });
      },
    },
  });

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
            onPress={() => {
              undo.reset();
              setUndoing(e);
            }}
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
        <StateMessage
          testID="edits-empty"
          title="Pick a gig"
          detail="Search for it by act or title, or paste its id from the Fan gigs tab or the gig page."
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
      {undoing && gig ? (
        <ConfirmDialog
          visible
          title="Undo this edit?"
          changes={[`On ${gigLine(gig)}:`, ...undoChanges(undoing)]}
          confirmLabel="Undo"
          destructive
          busy={undo.isPending}
          error={undo.isError ? problemLines(undo.error).join(' · ') : null}
          onConfirm={() => undo.mutate({ id: undoing.id })}
          onCancel={() => setUndoing(null)}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
});
