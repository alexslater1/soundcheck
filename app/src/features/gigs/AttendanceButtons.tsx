import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import {
  getGetGigQueryKey,
  useClearGigAttendance,
  useSetGigAttendance,
} from '@/api/generated/catalog/catalog';
import type { GigDetail } from '@/api/generated/model';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { applyAttendance, ATTENDANCE_OPTIONS, type AttendanceStatus } from './attendance';

/**
 * Query keys a new mark makes stale: every gig list (`/gigs`, `/me/gigs`, an artist's or a venue's gigs,
 * the gigs behind a stats line `/users/{id}/stats/gigs`), the stats counted from Went gigs
 * (`/users/{id}/stats`) and how many gigs a line stands for (`/users/{id}/stats/gigs/count`,
 * "You've seen Keo 5 times").
 */
export const isGigList = (key: readonly unknown[]) =>
  typeof key[0] === 'string' &&
  /\/gigs$|\/gigs\/going$|\/stats$|\/stats\/gigs\/count$/.test(key[0]);

/**
 * Interested / Going / Went with an optimistic update: the button and the counts change
 * the moment it is pressed; if the request fails, the previous state is put back and a message shown.
 * Pressing the selected status again clears it.
 */
export function AttendanceButtons({ detail }: { detail: GigDetail }) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const gigId = detail.gig.id;
  const key = getGetGigQueryKey(gigId);
  const [failed, setFailed] = useState(false);
  const set = useSetGigAttendance();
  const clear = useClearGigAttendance();

  // One change at a time: two requests in flight could arrive in the wrong order, and the rollback of a
  // late failure would overwrite the result of the newer one.
  const busy = set.isPending || clear.isPending;

  async function choose(status: AttendanceStatus) {
    if (busy) return;
    const next = detail.gig.myAttendance === status ? null : status;
    setFailed(false);
    await client.cancelQueries({ queryKey: key });
    const previous = client.getQueryData<GigDetail>(key);
    client.setQueryData<GigDetail>(key, (old) => (old ? applyAttendance(old, next) : old));
    try {
      if (next) {
        // The server's answer is the truth: its counts replace the optimistic ones.
        const saved = await set.mutateAsync({ id: gigId, data: { status: next } });
        client.setQueryData<GigDetail>(key, (old) =>
          old
            ? {
                ...old,
                gig: { ...old.gig, myAttendance: saved.myAttendance },
                attendanceCounts: saved.attendanceCounts,
              }
            : old,
        );
      } else {
        await clear.mutateAsync({ id: gigId });
      }
      void client.invalidateQueries({ predicate: (query) => isGigList(query.queryKey) });
    } catch {
      client.setQueryData(key, previous);
      setFailed(true);
    }
  }

  const counts = detail.attendanceCounts;
  return (
    <View style={styles.box}>
      <View style={styles.row}>
        {ATTENDANCE_OPTIONS.map((option) => (
          <Chip
            key={option.status}
            label={option.label}
            selected={detail.gig.myAttendance === option.status}
            disabled={busy}
            onPress={() => void choose(option.status)}
          />
        ))}
      </View>
      <Text variant="caption" muted testID="attendance-counts">
        {counts.going} going · {counts.interested} interested · {counts.went} went
      </Text>
      {failed ? (
        <Text variant="caption" style={{ color: colors.danger }} testID="attendance-error">
          Could not save that. Check your connection and try again.
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  row: { flexDirection: 'row', flexWrap: 'wrap' },
});
