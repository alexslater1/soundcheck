import Ionicons from '@expo/vector-icons/Ionicons';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { useListGigs, useListGigsToPost } from '@/api/generated/catalog/catalog';
import type { GigSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { TextField } from '@/components/ui/TextField';
import { dateParts, formatGigDate } from '@/features/gigs/format';
import {
  FilterPill,
  SearchSuggestions,
  type PickedFilter,
} from '@/features/gigs/SearchSuggestions';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { AddGigForm } from './AddGigForm';
import { searchWindow } from './gigPicker';

/**
 * "Which gig?" on the Create tab. Before anything is typed it suggests the gigs a clip was most
 * likely filmed at (`GET /me/gigs/to-post`): gigs the user marked Going or Went and gigs of acts
 * they follow near home, from the last seven days, newest first. Typing searches every gig of the
 * past year by artist, venue or title, newest first and UK-wide; an artist or venue from the
 * suggestions narrows it exactly. When the gig is not there, "Add the gig" creates it.
 */
export function GigPicker({
  value,
  onChange,
}: {
  value: GigSummary | null;
  onChange: (gig: GigSummary | null) => void;
}) {
  const { colors } = useTheme();
  const [window] = useState(() => searchWindow(new Date()));
  const [text, setText] = useState('');
  const [filter, setFilter] = useState<PickedFilter | null>(null);
  const [adding, setAdding] = useState(false);
  const q = useDebounced(text.trim(), 300);
  const searching = q.length >= 2 || filter !== null;

  const suggested = useListGigsToPost();
  const found = useListGigs(
    {
      ...window,
      ...(filter?.kind === 'artist' ? { artistIds: [filter.id] } : {}),
      ...(filter?.kind === 'venue' ? { venueIds: [filter.id] } : {}),
      ...(filter ? {} : { q }),
      nationwide: true,
      order: 'desc',
      limit: 20,
    },
    { query: { enabled: searching } },
  );

  if (value) {
    return (
      <View style={styles.box}>
        <GigRow gig={value} selected />
        <Button title="Change gig" variant="secondary" onPress={() => onChange(null)} />
      </View>
    );
  }
  if (adding) {
    return (
      <AddGigForm
        initialAct={filter?.kind === 'artist' ? undefined : text.trim() || undefined}
        onAdded={(gig) => {
          setAdding(false);
          onChange(gig);
        }}
        onCancel={() => setAdding(false)}
      />
    );
  }

  const results = searching ? (found.data?.items ?? []) : (suggested.data ?? []);
  const loading = searching ? found.isFetching : suggested.isPending;
  const failed = searching ? found.isError : suggested.isError;

  return (
    <View style={styles.box} testID="gig-picker">
      {filter ? (
        <View style={styles.row}>
          <FilterPill filter={filter} onClear={() => setFilter(null)} />
        </View>
      ) : (
        <>
          <TextField
            value={text}
            onChangeText={setText}
            placeholder="Search by artist, venue or gig"
            accessibilityLabel="Search for the gig"
            autoCorrect={false}
          />
          <SearchSuggestions
            q={q}
            onPick={(picked) => {
              setFilter(picked);
              setText('');
            }}
          />
        </>
      )}
      <Text variant="label" muted>
        {searching ? 'MATCHING GIGS, NEWEST FIRST' : 'THIS WEEK: YOUR GIGS AND YOUR ACTS’ GIGS'}
      </Text>
      {loading && results.length === 0 ? (
        <Text muted>Looking for gigs…</Text>
      ) : failed && results.length === 0 ? (
        <View style={styles.box} testID="compose-gigs-error">
          <Text muted>The gigs could not be loaded.</Text>
          <Button
            title="Try again"
            size="small"
            variant="secondary"
            onPress={() => void (searching ? found.refetch() : suggested.refetch())}
          />
        </View>
      ) : results.length === 0 ? (
        <Text muted testID="compose-no-gigs">
          {searching
            ? 'No gig matches that.'
            : 'No gigs this week from you or the acts you follow. Search for it.'}
        </Text>
      ) : (
        <View accessibilityRole="radiogroup" style={styles.list}>
          {results.map((gig) => (
            <GigRow key={gig.id} gig={gig} onPress={() => onChange(gig)} showMark />
          ))}
        </View>
      )}
      <Pressable
        accessibilityRole="button"
        onPress={() => setAdding(true)}
        style={[styles.add, { borderColor: colors.accent }]}
        testID="add-gig"
      >
        <Ionicons name="add-circle-outline" size={20} color={colors.accent} />
        <Text style={{ color: colors.accent, fontWeight: '700' }}>
          Can&apos;t find it? Add the gig
        </Text>
      </Pressable>
    </View>
  );
}

/** A gig as one compact, tappable line: date tile, title, venue and date. */
export function GigRow({
  gig,
  selected = false,
  onPress,
  showMark = false,
}: {
  gig: GigSummary;
  selected?: boolean;
  onPress?: () => void;
  /** Say when the user marked it Going or Went (Create's suggestions). */
  showMark?: boolean;
}) {
  const { colors } = useTheme();
  const when = dateParts(gig.startsAt);
  const mark =
    showMark && gig.myAttendance === 'WENT'
      ? 'You went'
      : showMark && gig.myAttendance === 'GOING'
        ? 'You were going'
        : null;
  return (
    <Pressable
      role={onPress ? 'radio' : undefined}
      aria-checked={onPress ? selected : undefined}
      accessibilityLabel={`${gig.title} · ${gig.venue.name}`}
      disabled={!onPress}
      onPress={onPress}
      style={[
        styles.gig,
        {
          backgroundColor: selected ? colors.accentSoft : colors.surface,
          borderColor: selected ? colors.accent : colors.border,
        },
      ]}
    >
      <View
        style={[styles.tile, { backgroundColor: selected ? colors.accent : colors.accentSoft }]}
      >
        <Text variant="label" style={{ color: selected ? colors.onAccent : colors.accent }}>
          {when.month}
        </Text>
        <Text style={[styles.day, selected ? { color: colors.onAccent } : null]}>{when.day}</Text>
      </View>
      <View style={styles.text}>
        <Text style={styles.title} numberOfLines={1}>
          {gig.title}
        </Text>
        <Text variant="caption" muted numberOfLines={1}>
          {gig.venue.name}
          {gig.venue.city ? `, ${gig.venue.city}` : ''} · {formatGigDate(gig.startsAt)}
        </Text>
        {mark ? (
          <Text variant="caption" style={{ color: colors.accent }}>
            {mark}
          </Text>
        ) : null}
      </View>
      {selected ? <Ionicons name="checkmark-circle" size={22} color={colors.accent} /> : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  row: { flexDirection: 'row' },
  list: { gap: spacing.xs },
  gig: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.xs,
  },
  tile: {
    width: 44,
    height: 44,
    borderRadius: radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  day: { fontWeight: '800', fontSize: 17, lineHeight: 20 },
  text: { flex: 1 },
  title: { fontWeight: '700' },
  add: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderRadius: radius.md,
    padding: spacing.md,
  },
});
