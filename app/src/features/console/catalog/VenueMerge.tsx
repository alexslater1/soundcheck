import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import {
  getAdminListSuspectVenuesQueryKey,
  useAdminMergeVenue,
  useAdminPreviewVenueMerge,
} from '@/api/generated/admin-catalog/admin-catalog';
import { getGig, useGetVenue, useSearchVenues } from '@/api/generated/catalog/catalog';
import type {
  GigSummary,
  VenueHit,
  VenueMergePreview,
  VenueMergeResult,
  VenueMergeSameNight,
  VenueMergeSide,
} from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ConfirmDialog } from '../ConfirmDialog';
import { problemLines } from '../ranking/configModel';
import { isGigId } from './mergeModel';
import type { VenueMergeDone } from './tabTypes';
import { useHandles, who } from './useHandles';
import { venueCountLabel, venueMergeChanges, venueMergeWarnings } from './venueModel';

type Picked = { id: string; name: string; city?: string | null };

/**
 * Merging a duplicate venue: pick the duplicate and the venue that stays, read the
 * preview, confirm. Gigs that end up on the same night at one venue are listed afterwards with a button
 * that opens the Merge tab on each pair; the venue merge never merges gigs itself. The result lives in the
 * Catalog page (`done`), so the list is still there after merging a pair in the Merge tab, with that pair
 * ticked.
 */
export function VenueMerge({
  openMerge,
  done,
  setDone,
  mergedGigIds,
}: {
  openMerge: (from: GigSummary, into: GigSummary) => void;
  done: VenueMergeDone | null;
  setDone: (done: VenueMergeDone | null) => void;
  mergedGigIds: ReadonlySet<string>;
}) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [from, setFrom] = useState<Picked | null>(null);
  const [into, setInto] = useState<Picked | null>(null);
  const [confirming, setConfirming] = useState(false);
  const same = from !== null && into !== null && from.id === into.id;
  const ready = from !== null && into !== null && !same;
  const preview = useAdminPreviewVenueMerge(
    from?.id ?? '',
    { into: into?.id ?? '' },
    { query: { enabled: ready, retry: false } },
  );
  const merge = useAdminMergeVenue();
  const handles = useHandles([preview.data?.from.addedBy, preview.data?.into.addedBy]);

  const pick = (side: 'from' | 'into') => (venue: Picked | null) => {
    setDone(null);
    merge.reset();
    (side === 'from' ? setFrom : setInto)(venue);
  };

  const confirm = () => {
    if (!from || !into) return;
    merge.mutate(
      { from: from.id, data: { into: into.id } },
      {
        onSuccess: (result) => {
          setConfirming(false);
          setDone({ result, name: into.name });
          setFrom(null);
          void client.invalidateQueries({ queryKey: getAdminListSuspectVenuesQueryKey() });
        },
      },
    );
  };

  return (
    <View style={styles.page} testID="venue-merge">
      <Text variant="label" muted>
        MERGE DUPLICATE VENUES
      </Text>
      <Text muted>
        Gigs, the sources&apos; ids, sibling rooms, saved searches and followers move to the venue
        that stays. The duplicate is deleted; its old link and its name lead to the one that stays.
      </Text>
      {done ? (
        <View style={styles.done} testID="venue-merge-done">
          <Text style={{ color: colors.success }}>
            Merged into {done.name}. Moved{' '}
            {done.result.moved
              .filter((m) => m.count > 0)
              .map(venueCountLabel)
              .join(', ') || 'nothing'}
            .
          </Text>
          <SameNight
            pairs={done.result.sameNight}
            openMerge={openMerge}
            mergedGigIds={mergedGigIds}
          />
        </View>
      ) : null}
      <View style={styles.pickers}>
        <VenuePicker
          label="Duplicate (will be deleted)"
          value={from}
          onChange={pick('from')}
          testID="venue-merge-from"
        />
        <VenuePicker
          label="Venue that stays"
          value={into}
          onChange={pick('into')}
          testID="venue-merge-into"
        />
      </View>
      {same ? <StateMessage title="That's the same venue twice" /> : null}
      {ready && preview.isPending ? <Skeleton height={180} /> : null}
      {ready && preview.isError ? (
        <StateMessage
          testID="venue-merge-preview-error"
          title="Couldn't preview this merge"
          detail={problemLines(preview.error).join(' · ')}
          onRetry={() => void preview.refetch()}
        />
      ) : null}
      {ready && preview.data ? (
        <Preview
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
          title="Merge these venues?"
          changes={venueMergeChanges(preview.data)}
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

function Preview({
  preview,
  handles,
  onMerge,
}: {
  preview: VenueMergePreview;
  handles: Record<string, string>;
  onMerge: () => void;
}) {
  const { colors } = useTheme();
  const blocked = preview.conflicts.length > 0;
  return (
    <View style={styles.page} testID="venue-merge-preview">
      <View style={styles.pickers}>
        <Side title="Duplicate (deleted)" side={preview.from} handles={handles} />
        <Side title="Stays" side={preview.into} handles={handles} />
      </View>
      {venueMergeWarnings(preview).map((w) => (
        <Text key={w} style={{ color: colors.warning }} testID="venue-merge-warning">
          ⚠ {w}
        </Text>
      ))}
      {blocked ? (
        <View style={[styles.box, { borderColor: colors.danger }]} accessibilityRole="alert">
          {preview.conflicts.map((c) => (
            <Text key={c}>• {c}</Text>
          ))}
        </View>
      ) : null}
      <View style={[styles.box, { borderColor: colors.border }]}>
        <Text variant="label" muted>
          WHAT MOVES TO THE VENUE THAT STAYS
        </Text>
        {preview.moving.filter((m) => m.count > 0).length === 0 ? (
          <Text muted>Nothing: the duplicate has no gigs or followers.</Text>
        ) : (
          preview.moving
            .filter((m) => m.count > 0)
            .map((m) => <Text key={m.what}>• {venueCountLabel(m)}</Text>)
        )}
      </View>
      <View>
        <Button
          title="Merge…"
          variant="danger"
          disabled={blocked}
          onPress={onMerge}
          testID="venue-merge-start"
        />
      </View>
    </View>
  );
}

function Side({
  title,
  side,
  handles,
}: {
  title: string;
  side: VenueMergeSide;
  handles: Record<string, string>;
}) {
  const { colors } = useTheme();
  return (
    <View style={[styles.side, { borderColor: colors.border }]}>
      <Text variant="label" muted>
        {title.toUpperCase()}
      </Text>
      <Text variant="title">{side.name}</Text>
      <Text variant="caption" muted>
        {[side.city, side.postcode].filter(Boolean).join(' · ') || 'No address'}
      </Text>
      <Text variant="caption" muted>
        {side.addedBy ? `Added by ${who(handles, side.addedBy)}` : 'From a source listing'}
      </Text>
      <Text variant="caption">
        {side.externalIds.length === 0 ? 'No source ids' : side.externalIds.join(' · ')}
      </Text>
      {side.counts
        .filter((c) => c.count > 0)
        .map((c) => (
          <Text key={c.what} variant="caption">
            {venueCountLabel(c)}
          </Text>
        ))}
      <Text variant="caption" muted selectable>
        {side.id}
      </Text>
    </View>
  );
}

/**
 * Gigs now on the same night at the venue that stays: each opens the Merge tab on that pair, and a pair
 * merged since (either gig merged away) is ticked instead.
 */
function SameNight({
  pairs,
  openMerge,
  mergedGigIds,
}: {
  pairs: VenueMergeSameNight[];
  openMerge: (from: GigSummary, into: GigSummary) => void;
  mergedGigIds: ReadonlySet<string>;
}) {
  const { colors } = useTheme();
  const [error, setError] = useState<string | null>(null);
  if (pairs.length === 0) return null;
  const open = async (pair: VenueMergeSameNight) => {
    setError(null);
    try {
      const [moved, stays] = await Promise.all([getGig(pair.movedGigId), getGig(pair.gigId)]);
      openMerge(moved.gig, stays.gig);
    } catch (e) {
      setError(problemLines(e).join(' · '));
    }
  };
  return (
    <View style={styles.done} testID="venue-merge-same-night">
      <Text style={{ color: colors.warning }}>
        These now share a night at one venue. Merge them if they are one show:
      </Text>
      {pairs.map((p) => (
        <View key={`${p.movedGigId}:${p.gigId}`} style={styles.pair}>
          <Text variant="caption">
            {p.day}: {p.movedTitle} / {p.title}
          </Text>
          {mergedGigIds.has(p.movedGigId) || mergedGigIds.has(p.gigId) ? (
            <Text
              variant="caption"
              style={{ color: colors.success }}
              testID={`venue-merge-pair-done-${p.movedGigId}`}
            >
              Merged ✓
            </Text>
          ) : (
            <Pressable
              accessibilityRole="button"
              onPress={() => void open(p)}
              testID={`venue-merge-pair-${p.movedGigId}`}
            >
              <Text variant="caption" style={{ color: colors.accent }}>
                Merge these…
              </Text>
            </Pressable>
          )}
        </View>
      ))}
      {error ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {error}
        </Text>
      ) : null}
    </View>
  );
}

/** Picks one venue with the app's venue search (`GET /venues?q=`), or a pasted venue id. */
function VenuePicker({
  label,
  value,
  onChange,
  testID,
}: {
  label: string;
  value: Picked | null;
  onChange: (venue: Picked | null) => void;
  testID: string;
}) {
  const { colors } = useTheme();
  const [text, setText] = useState('');
  const q = useDebounced(text.trim(), 300);
  const byId = isGigId(q); // any UUID: a venue id pasted from the venue page or the suspect list
  const found = useSearchVenues(
    { q },
    { query: { enabled: !byId && q.length >= 2 && value === null } },
  );
  const pasted = useGetVenue(q, { query: { enabled: byId && value === null } });

  if (value) {
    return (
      <View style={styles.side} testID={testID}>
        <Text variant="label" muted>
          {label.toUpperCase()}
        </Text>
        <Text>
          {value.name}
          {value.city ? ` · ${value.city}` : ''}
        </Text>
        <Button
          title="Change"
          variant="secondary"
          size="small"
          onPress={() => onChange(null)}
          testID={`${testID}-change`}
        />
      </View>
    );
  }
  const results: VenueHit[] = byId
    ? pasted.data
      ? [{ id: pasted.data.id, name: pasted.data.name, city: pasted.data.city }]
      : []
    : q.length >= 2
      ? (found.data ?? [])
      : [];
  const loading = byId ? pasted.isFetching : q.length >= 2 && found.isFetching;
  const failed = byId ? pasted.isError : q.length >= 2 && found.isError;
  return (
    <View style={styles.side} testID={testID}>
      <Text variant="label" muted>
        {label.toUpperCase()}
      </Text>
      <TextInput
        accessibilityLabel={label}
        placeholder="Search venues by name, or paste a venue id"
        placeholderTextColor={colors.textMuted}
        value={text}
        onChangeText={setText}
        testID={`${testID}-search`}
        style={[
          styles.input,
          { color: colors.text, borderColor: colors.border, backgroundColor: colors.surface },
        ]}
      />
      {loading ? <Skeleton height={44} /> : null}
      {failed ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {byId ? 'No venue has that id.' : "The search didn't answer. Try again."}
        </Text>
      ) : null}
      {q.length >= 2 && !loading && !failed && results.length === 0 ? (
        <Text variant="caption" muted>
          No venue matches &quot;{q}&quot;.
        </Text>
      ) : null}
      {results.map((v) => (
        <Pressable
          key={v.id}
          accessibilityRole="button"
          onPress={() => onChange({ id: v.id, name: v.name, city: v.city })}
          testID={`${testID}-result-${v.id}`}
          style={[styles.result, { borderColor: colors.border }]}
        >
          <Text>{v.name}</Text>
          <Text variant="caption" muted>
            {v.city ?? 'No town'} · {v.id.slice(0, 8)}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  pickers: { flexDirection: 'row', gap: spacing.md, alignItems: 'flex-start', flexWrap: 'wrap' },
  side: {
    flex: 1,
    minWidth: 320,
    gap: spacing.xs,
    borderWidth: 0,
    borderRadius: radius.md,
  },
  box: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.xs },
  done: { gap: spacing.xs },
  pair: { flexDirection: 'row', gap: spacing.sm, alignItems: 'center', flexWrap: 'wrap' },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    fontSize: 16,
  },
  result: { borderWidth: 1, borderRadius: radius.md, padding: spacing.sm, gap: 2 },
});
