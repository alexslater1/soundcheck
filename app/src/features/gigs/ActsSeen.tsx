import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { getGetGigQueryKey, useSetGigActsSeen } from '@/api/generated/catalog/catalog';
import type { GigDetail } from '@/api/generated/model';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { isGigList } from './AttendanceButtons';

/** The acts of the bill the fan saw: every act but the ones they unticked. */
export function seenActs(detail: GigDetail): string[] {
  const missed = new Set(detail.gig.myActsMissed);
  return detail.lineup.map((act) => act.id).filter((id) => !missed.has(id));
}

/**
 * "Which acts did you see?" on a gig the fan went to with more than one act. Every act is
 * ticked to begin with; untick the ones you missed, and your stats and "Times seen" count only the rest.
 * At least one stays ticked. The change shows at once and is put back if the server refuses it.
 */
export function ActsSeen({ detail }: { detail: GigDetail }) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const save = useSetGigActsSeen();
  const [failed, setFailed] = useState(false);
  const key = getGetGigQueryKey(detail.gig.id);

  if (detail.gig.myAttendance !== 'WENT' || detail.lineup.length < 2) return null;
  const seen = seenActs(detail);

  async function toggle(actId: string) {
    const next = seen.includes(actId) ? seen.filter((id) => id !== actId) : [...seen, actId];
    if (next.length === 0 || save.isPending) return;
    setFailed(false);
    const previous = client.getQueriesData<GigDetail>({ queryKey: key });
    const missed = detail.lineup.map((act) => act.id).filter((id) => !next.includes(id));
    client.setQueriesData<GigDetail>({ queryKey: key }, (old) =>
      old ? { ...old, gig: { ...old.gig, myActsMissed: missed, wentUnconfirmed: false } } : old,
    );
    try {
      await save.mutateAsync({ id: detail.gig.id, data: { artistIds: next } });
      void client.invalidateQueries({ predicate: (query) => isGigList(query.queryKey) });
    } catch {
      for (const [cached, data] of previous) client.setQueryData(cached, data);
      setFailed(true);
    }
  }

  return (
    <View style={styles.box} testID="acts-seen">
      <Text variant="label" muted>
        WHICH ACTS DID YOU SEE?
      </Text>
      <View style={styles.row}>
        {detail.lineup.map((act) => (
          <Chip
            key={act.id}
            label={act.name}
            selected={seen.includes(act.id)}
            disabled={save.isPending}
            onPress={() => void toggle(act.id)}
          />
        ))}
      </View>
      <Text variant="caption" muted>
        Untick any you missed: your stats count only the acts you saw.
      </Text>
      {failed ? (
        <Text variant="caption" style={{ color: colors.danger }} testID="acts-seen-error">
          Could not save that. Try again.
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.xs },
  row: { flexDirection: 'row', flexWrap: 'wrap' },
});
