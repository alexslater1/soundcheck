import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { useListUnpostedGigs } from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { spacing } from '@/theme/tokens';

import { GigRow } from './GigPicker';

/** How many pending gigs show before "Show all". */
const FOLDED = 3;

/**
 * H44's Pending list: gigs the user went to (said "I was there", or marked Went) and has not posted from
 * yet, newest first, with no time limit. Tapping one starts a post for it. On Create it is a quiet
 * section that disappears when there is nothing pending; on My gigs it says so.
 */
export function PendingGigs({
  onPick,
  showEmpty = false,
}: {
  onPick: (gig: GigSummary) => void;
  showEmpty?: boolean;
}) {
  const pending = useListUnpostedGigs();
  const [open, setOpen] = useState(false);

  if (pending.isPending) {
    return showEmpty ? (
      <Text muted testID="pending-loading">
        Looking for gigs you have not posted from…
      </Text>
    ) : null;
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
    return showEmpty ? (
      <Text muted testID="pending-empty">
        Nothing pending: every gig you went to has your clips.
      </Text>
    ) : null;
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
