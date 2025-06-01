import Ionicons from '@expo/vector-icons/Ionicons';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { addGig } from '@/api/generated/catalog/catalog';
import type { GigSummary, PastGig } from '@/api/generated/model';
import { useSearchPastGigs } from '@/api/generated/past-gigs/past-gigs';
import { ApiError } from '@/api/http';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { TextField } from '@/components/ui/TextField';
import { dateParts, formatGigDate } from '@/features/gigs/format';
import { openWebLink } from '@/lib/openLink';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { pastGigRequest, yearChoices } from './pastGig';

/**
 * "Was it one of these?": an artist's past UK concerts from setlist.fm. Tapping one adds the
 * gig (or finds it, if someone added it before) and selects it. Nothing from setlist.fm is kept except
 * the setlist's id and link; the attribution links back, as their terms ask. Hidden when the server
 * has no setlist.fm key.
 */
export function PastGigSearch({
  initialArtist,
  onAdded,
}: {
  initialArtist?: string;
  onAdded: (gig: GigSummary) => void;
}) {
  const { colors } = useTheme();
  const [text, setText] = useState(initialArtist ?? '');
  const [year, setYear] = useState<number | null>(null);
  const [adding, setAdding] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const artist = useDebounced(text.trim(), 400);
  const search = useSearchPastGigs(
    { artist, ...(year ? { year } : {}) },
    { query: { enabled: artist.length >= 2, retry: false, staleTime: 10 * 60 * 1000 } },
  );

  const unavailable =
    search.error instanceof ApiError && search.error.code === 'PAST_GIGS_UNAVAILABLE';
  if (unavailable) {
    return null;
  }

  const choose = async (gig: PastGig) => {
    const request = pastGigRequest(gig);
    if (!request) {
      return;
    }
    setAdding(gig.setlistId);
    setProblem(null);
    try {
      onAdded((await addGig(request)).item);
    } catch (error) {
      setProblem(
        error instanceof ApiError ? error.message : 'That gig could not be added. Try again.',
      );
    } finally {
      setAdding(null);
    }
  };

  const results = search.data?.items ?? [];
  return (
    <View style={[styles.box, { borderColor: colors.border }]} testID="past-gig-search">
      <Text variant="label" muted>
        WAS IT A PAST GIG? FIND IT ON SETLIST.FM
      </Text>
      <TextField
        value={text}
        onChangeText={setText}
        placeholder="Artist"
        accessibilityLabel="Artist on setlist.fm"
        autoCorrect={false}
      />
      <View style={styles.wrap}>
        {yearChoices(new Date()).map((y) => (
          <Chip
            key={y ?? 'any'}
            label={y === null ? 'Any year' : String(y)}
            selected={year === y}
            onPress={() => setYear(y)}
          />
        ))}
      </View>
      {artist.length < 2 ? null : search.isFetching && results.length === 0 ? (
        <Text muted>Looking on setlist.fm…</Text>
      ) : search.isError ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {search.error instanceof ApiError
            ? search.error.message
            : 'setlist.fm could not be reached.'}
        </Text>
      ) : results.length === 0 ? (
        <Text muted testID="past-gigs-empty">
          No UK gigs of “{artist}” on setlist.fm{year ? ` in ${year}` : ''}. Add it by hand below.
        </Text>
      ) : (
        results.map((gig) => {
          const when = dateParts(`${gig.date}T12:00:00Z`);
          const placeable = gig.lat != null && gig.lng != null;
          return (
            <View
              key={gig.setlistId}
              style={[styles.row, { backgroundColor: colors.surface, borderColor: colors.border }]}
            >
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`It was ${gig.artistName} at ${gig.venueName} on ${gig.date}`}
                disabled={!placeable || adding !== null}
                onPress={() => void choose(gig)}
                style={styles.pick}
              >
                <View style={[styles.tile, { backgroundColor: colors.accentSoft }]}>
                  <Text variant="label" style={{ color: colors.accent }}>
                    {when.month}
                  </Text>
                  <Text style={styles.day}>{when.day}</Text>
                </View>
                <View style={styles.text}>
                  <Text style={styles.bold} numberOfLines={1}>
                    {gig.venueName}
                    {gig.cityName ? `, ${gig.cityName}` : ''}
                  </Text>
                  <Text variant="caption" muted numberOfLines={1}>
                    {formatGigDate(`${gig.date}T12:00:00Z`)}
                    {gig.tour ? ` · ${gig.tour}` : ''}
                  </Text>
                  {adding === gig.setlistId ? (
                    <Text variant="caption">Adding…</Text>
                  ) : !placeable ? (
                    <Text variant="caption" muted>
                      No location on setlist.fm: add it by hand.
                    </Text>
                  ) : null}
                </View>
              </Pressable>
              {gig.url ? (
                <Pressable
                  accessibilityRole="link"
                  accessibilityLabel="View on setlist.fm"
                  onPress={() => void openWebLink(gig.url as string)}
                  style={styles.link}
                >
                  <Ionicons name="open-outline" size={18} color={colors.textMuted} />
                </Pressable>
              ) : null}
            </View>
          );
        })
      )}
      {problem ? (
        <Text variant="caption" style={{ color: colors.danger }} accessibilityRole="alert">
          {problem}
        </Text>
      ) : null}
      <Text
        variant="caption"
        muted
        accessibilityRole="link"
        onPress={() => void openWebLink('https://www.setlist.fm')}
      >
        Concert history from setlist.fm
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth, paddingBottom: spacing.md },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.xs,
  },
  pick: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  tile: {
    width: 44,
    height: 44,
    borderRadius: radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  day: { fontWeight: '800', fontSize: 17, lineHeight: 20 },
  text: { flex: 1 },
  bold: { fontWeight: '700' },
  link: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
});
