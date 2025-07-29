import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import {
  getGetGigQueryKey,
  useClearGigAttendance,
  useSetGigAttendance,
} from '@/api/generated/catalog/catalog';
import type { GigDetail } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import {
  applyAttendance,
  attendanceOptions,
  countsLine,
  gigPhase,
  nextAttendance,
  type AttendanceStatus,
} from './attendance';

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
 * Pressing the selected status again clears it. Only the statuses the gig's day allows are offered:
 * Interested and Going before it, Went after it, all three on the day, none once cancelled.
 * A Going whose gig is over reads as Went, and the fan is asked "Did you go?".
 */
export function AttendanceButtons({ detail }: { detail: GigDetail }) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const gigId = detail.gig.id;
  // Every cached copy of this gig: the page keys it with the viewer's position when it has one.
  const key = getGetGigQueryKey(gigId);
  const [failed, setFailed] = useState(false);
  const set = useSetGigAttendance();
  const clear = useClearGigAttendance();

  // One change at a time: two requests in flight could arrive in the wrong order, and the rollback of a
  // late failure would overwrite the result of the newer one.
  const busy = set.isPending || clear.isPending;

  async function choose(status: AttendanceStatus | null, tapped = true) {
    if (busy) return;
    const next = tapped && status ? nextAttendance(detail.gig, status) : status;
    setFailed(false);
    await client.cancelQueries({ queryKey: key });
    const previous = client.getQueriesData<GigDetail>({ queryKey: key });
    client.setQueriesData<GigDetail>({ queryKey: key }, (old) =>
      old ? applyAttendance(old, next) : old,
    );
    try {
      if (next) {
        // The server's answer is the truth: its counts replace the optimistic ones.
        const saved = await set.mutateAsync({ id: gigId, data: { status: next } });
        client.setQueriesData<GigDetail>({ queryKey: key }, (old) =>
          old
            ? {
                ...old,
                gig: { ...old.gig, myAttendance: saved.myAttendance, wentUnconfirmed: false },
                attendanceCounts: saved.attendanceCounts,
              }
            : old,
        );
      } else {
        await clear.mutateAsync({ id: gigId });
      }
      void client.invalidateQueries({ predicate: (query) => isGigList(query.queryKey) });
    } catch {
      for (const [cached, data] of previous) client.setQueryData(cached, data);
      setFailed(true);
    }
  }

  const phase = gigPhase(detail.gig.startsAt);
  const options = attendanceOptions(phase, detail.gig.status);
  return (
    <View style={styles.box}>
      {options.length > 0 ? (
        <View style={styles.row}>
          {options.map((option) => (
            <Chip
              key={option.status}
              label={option.label}
              selected={detail.gig.myAttendance === option.status}
              disabled={busy}
              onPress={() => void choose(option.status)}
            />
          ))}
        </View>
      ) : null}
      {detail.gig.wentUnconfirmed ? (
        <DidYouGo
          busy={busy}
          onYes={() => void choose('WENT', false)}
          onNo={() => void choose(null, false)}
        />
      ) : null}
      <Text variant="caption" muted testID="attendance-counts">
        {countsLine(detail.attendanceCounts, phase)}
      </Text>
      {failed ? (
        <Text variant="caption" style={{ color: colors.danger }} testID="attendance-error">
          Could not save that. Check your connection and try again.
        </Text>
      ) : null}
    </View>
  );
}

/**
 * "You said Going. Did you go?" for a Going whose gig is over: Yes keeps it as Went (now the
 * fan's own word), No removes it. Shown on the gig page and in Pending.
 */
export function DidYouGo({
  busy,
  onYes,
  onNo,
}: {
  busy: boolean;
  onYes: () => void;
  onNo: () => void;
}) {
  return (
    <View style={styles.ask} testID="did-you-go">
      <Text variant="caption" muted style={styles.askText}>
        You said Going. Did you go?
      </Text>
      <Button title="Yes, I went" size="small" disabled={busy} onPress={onYes} />
      <Button title="No" size="small" variant="secondary" disabled={busy} onPress={onNo} />
    </View>
  );
}

const styles = StyleSheet.create({
  ask: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, flexWrap: 'wrap' },
  askText: { flexShrink: 1 },
  box: { gap: spacing.sm },
  row: { flexDirection: 'row', flexWrap: 'wrap' },
});
