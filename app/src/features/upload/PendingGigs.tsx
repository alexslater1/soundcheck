import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { useListUnpostedGigs } from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { spacing } from '@/theme/tokens';

import { GigRow } from './GigPicker';

/** The number on the Create tab's badge: gigs waiting for clips (0 while loading or on error). */
export function usePendingGigCount(): number {
  return useListUnpostedGigs().data?.length ?? 0;
}

/** How many pending gigs show before "Show all". */
const FOLDED = 3;

/**
 * H44's Pending list: gigs the user went to (said "I was there", or marked Went) and has not posted from
 * yet, newest first, with no time limit. Tapping one starts a post for it. It lives on Create only (it was
 * also on My gigs). It disappears when there is nothing pending, and the Create tab's badge counts it
 * (`usePendingGigCount`).
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
      {shown.map((gig) => (
        <GigRow key={gig.id} gig={gig} onPress={() => onPick(gig)} />
      ))}
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

const styles = StyleSheet.create({
  box: { gap: spacing.xs },
});
