import { useIsFocused } from '@react-navigation/native';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import {
  getGetGigQueryKey,
  useClearGigAttendance,
  useSetGigAttendance,
} from '@/api/generated/catalog/catalog';
import type { GigSummary } from '@/api/generated/model';
import { getListUnpostedGigsQueryKey, useListUnpostedGigs } from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { DidYouGo, isGigList } from '@/features/gigs/AttendanceButtons';
import { spacing } from '@/theme/tokens';

import { GigRow } from './GigPicker';
import { unseenCount, usePendingSeen } from './pendingSeen';

/**
 * The number on the Create tab's badge: gigs waiting for clips that the user has not seen on Create yet
 * (0 while loading or on error). Opening the tab clears it (`useMarkPendingGigsSeen`).
 */
export function usePendingGigCount(userId: string | null): number {
  const gigs = useListUnpostedGigs().data;
  const seen = usePendingSeen((state) => (userId ? state.seen[userId] : undefined));
  return gigs && userId ? unseenCount(gigs.map((gig) => gig.id), seen) : 0;
}

/**
 * While the Create tab is open, every pending gig counts as seen, so its badge goes. The gigs stay
 * in the list until the user posts from them or says they did not go.
 */
export function useMarkPendingGigsSeen(userId: string | null): void {
  const gigs = useListUnpostedGigs().data;
  const focused = useIsFocused();
  const markSeen = usePendingSeen((state) => state.markSeen);
  // One string, so a refetch with the same gigs does not write again.
  const ids = gigs?.map((gig) => gig.id).join(',');
  useEffect(() => {
    if (focused && userId && ids !== undefined) {
      markSeen(userId, ids === '' ? [] : ids.split(','));
    }
  }, [focused, userId, ids, markSeen]);
}

/** How many pending gigs show before "Show all". */
const FOLDED = 3;

/**
 * The Pending list: gigs the user went to (said "I was there", or marked Went) and has not posted
 * from yet, newest first, with no time limit. Tapping one starts a post for it. It lives on Create
 * only. It disappears when there is nothing pending, and the Create tab's badge counts the gigs not
 * seen here yet (`usePendingGigCount`). A gig the user said Going to that is now over is listed
 * too, asking "Did you go?": Yes confirms the Went, No removes the mark and the gig leaves the
 * list.
 */
export function PendingGigs({ onPick }: { onPick: (gig: GigSummary) => void }) {
  const pending = useListUnpostedGigs();
  const [open, setOpen] = useState(false);

  if (pending.isPending) {
    return null;
  }
  if (pending.isError) {
    return (
      <View style={styles.box} testID="pending-error">
        <Text muted>Your pending gigs could not be loaded.</Text>
        <Button
          title="Try again"
          size="small"
          variant="secondary"
          onPress={() => void pending.refetch()}
        />
      </View>
    );
  }
  const gigs = pending.data;
  if (gigs.length === 0) {
    return null;
  }
  const shown = open ? gigs : gigs.slice(0, FOLDED);
  return (
    <View style={styles.box} testID="pending-gigs">
      <Text variant="label" muted>
        PENDING · YOU WENT, NO CLIPS YET
      </Text>
      {shown.map((gig) =>
        gig.wentUnconfirmed ? (
          <View key={gig.id} style={styles.box}>
            <GigRow gig={gig} onPress={() => onPick(gig)} />
            <ConfirmWent gigId={gig.id} />
          </View>
        ) : (
          <GigRow key={gig.id} gig={gig} onPress={() => onPick(gig)} />
        ),
      )}
      {gigs.length > FOLDED ? (
        <Button
          title={open ? 'Show fewer' : `Show all ${gigs.length}`}
          size="small"
          variant="secondary"
          onPress={() => setOpen((now) => !now)}
        />
      ) : null}
    </View>
  );
}

/** "Did you go?" under a pending gig the user only said Going to. */
function ConfirmWent({ gigId }: { gigId: string }) {
  const client = useQueryClient();
  const set = useSetGigAttendance();
  const clear = useClearGigAttendance();
  const [failed, setFailed] = useState(false);
  const busy = set.isPending || clear.isPending;

  async function answer(went: boolean) {
    setFailed(false);
    try {
      if (went) await set.mutateAsync({ id: gigId, data: { status: 'WENT' } });
      else await clear.mutateAsync({ id: gigId });
      void client.invalidateQueries({ queryKey: getListUnpostedGigsQueryKey() });
      void client.invalidateQueries({ queryKey: getGetGigQueryKey(gigId) });
      void client.invalidateQueries({ predicate: (query) => isGigList(query.queryKey) });
    } catch {
      setFailed(true);
    }
  }

  return (
    <>
      <DidYouGo busy={busy} onYes={() => void answer(true)} onNo={() => void answer(false)} />
      {failed ? (
        <Text variant="caption" muted testID="did-you-go-error">
          Could not save that. Try again.
        </Text>
      ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.xs },
});
