import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import {
  getAdminListFanAddedGigsQueryKey,
  useAdminMergeGig,
  useAdminPreviewGigMerge,
} from '@/api/generated/admin-catalog/admin-catalog';
import type { GigMergeResult, GigSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { ConfirmDialog } from '../ConfirmDialog';
import { problemLines } from '../ranking/configModel';
import { GigSearchPicker } from './GigSearchPicker';
import { MergePreview } from './MergePreview';
import { countLabel, gigLine, mergeChanges } from './mergeModel';
import type { CatalogTabProps } from './tabTypes';
import { useHandles } from './useHandles';

/**
 * The Merge tab: pick the duplicate and the gig that stays, read the preview side by
 * side, and confirm. The server moves everything in one transaction or nothing at all.
 */
export function MergeTab({ pair }: CatalogTabProps) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [from, setFrom] = useState<GigSummary | null>(pair?.from ?? null);
  const [into, setInto] = useState<GigSummary | null>(pair?.into ?? null);
  const [confirming, setConfirming] = useState(false);
  const [done, setDone] = useState<{ result: GigMergeResult; into: GigSummary } | null>(null);

  const same = from !== null && into !== null && from.id === into.id;
  const ready = from !== null && into !== null && !same;
  const preview = useAdminPreviewGigMerge(
    from?.id ?? '',
    { into: into?.id ?? '' },
    { query: { enabled: ready, retry: false } },
  );
  const merge = useAdminMergeGig();
  const handles = useHandles([preview.data?.from.addedBy, preview.data?.into.addedBy]);

  const pick = (side: 'from' | 'into') => (gig: GigSummary | null) => {
    setDone(null);
    merge.reset();
    (side === 'from' ? setFrom : setInto)(gig);
  };

  const confirm = () => {
    if (!from || !into) return;
    merge.mutate(
      { from: from.id, data: { into: into.id } },
      {
        onSuccess: (result) => {
          setConfirming(false);
          setDone({ result, into });
          setFrom(null);
          void client.invalidateQueries({ queryKey: getAdminListFanAddedGigsQueryKey() });
        },
      },
    );
  };

  return (
    <View style={styles.page} testID="catalog-merge">
      <Text muted>
        Everything on the duplicate moves to the gig that stays: fans&apos; marks, the bill,
        listings, edits, posts, gig comments, alert records and match reviews. Then the duplicate is
        deleted and its old link opens the gig that stays.
      </Text>
      {done ? (
        <View style={styles.done} testID="merge-done">
          <Text style={{ color: colors.success }}>
            Merged into {gigLine(done.into)}. Moved{' '}
            {done.result.moved
              .filter((m) => m.count > 0)
              .map(countLabel)
              .join(', ') || 'nothing'}
            .
          </Text>
          <Link href={{ pathname: '/gig/[id]', params: { id: done.result.into } }}>
            <Text style={{ color: colors.accent }}>Open the gig that stays</Text>
          </Link>
        </View>
      ) : null}
      <View style={styles.pickers}>
        <GigSearchPicker
          label="Duplicate (will be deleted)"
          value={from}
          onChange={pick('from')}
          testID="merge-pick-from"
        />
        <View style={styles.swap}>
          <Button
            title="⇄ Swap"
            variant="secondary"
            size="small"
            disabled={!from && !into}
            onPress={() => {
              setFrom(into);
              setInto(from);
            }}
            testID="merge-swap"
          />
        </View>
        <GigSearchPicker
          label="Gig that stays"
          value={into}
          onChange={pick('into')}
          testID="merge-pick-into"
        />
      </View>
      {same ? (
        <StateMessage
          title="That's the same gig twice"
          detail="Pick the duplicate on the left and a different gig to keep on the right."
        />
      ) : null}
      {!ready && !same ? (
        <StateMessage
          testID="merge-empty"
          title="Pick two gigs"
          detail="The duplicate on the left, the gig to keep on the right. Fan gigs' “Merge into…” fills both."
        />
      ) : null}
      {ready && preview.isPending ? (
        <View style={styles.page} testID="merge-preview-loading">
          <Skeleton height={220} />
          <Skeleton height={80} />
        </View>
      ) : null}
      {ready && preview.isError ? (
        <StateMessage
          testID="merge-preview-error"
          title="Couldn't preview this merge"
          detail={problemLines(preview.error).join(' · ')}
          onRetry={() => void preview.refetch()}
        />
      ) : null}
      {ready && preview.data ? (
        <MergePreview
          preview={preview.data}
          handles={handles}
          onMerge={() => {
            merge.reset();
            setConfirming(true);
          }}
        />
      ) : null}
      {preview.data ? (
        <ConfirmDialog
          visible={confirming}
          title="Merge these gigs?"
          changes={mergeChanges(preview.data)}
          confirmLabel="Merge"
          destructive
          busy={merge.isPending}
          error={merge.isError ? problemLines(merge.error).join(' · ') : null}
          onConfirm={confirm}
          onCancel={() => setConfirming(false)}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  pickers: { flexDirection: 'row', gap: spacing.md, alignItems: 'flex-start', flexWrap: 'wrap' },
  swap: { paddingTop: spacing.lg },
  done: { gap: spacing.xs },
});
