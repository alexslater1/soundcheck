import Ionicons from '@expo/vector-icons/Ionicons';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { addGig, useListArtists, useSearchVenues } from '@/api/generated/catalog/catalog';
import type { GigSummary } from '@/api/generated/model';
import { useSearchCities } from '@/api/generated/reference/reference';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { TextField } from '@/components/ui/TextField';
import { MonthCalendar } from '@/features/calendar/MonthCalendar';
import { isoDate, monthOf, ukDate, type Month } from '@/features/calendar/monthGrid';
import { formatGigDate } from '@/features/gigs/format';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { addAct, parseTime, type ActChoice } from './gigPicker';
import { PastGigSearch } from './PastGigSearch';

type VenueChoice =
  | { kind: 'known'; id: string; name: string; city: string | null }
  | { kind: 'new'; name: string; cityId: number; city: string };

/**
 * "Can't find it? Add the gig": who played (acts the catalog knows, or new
 * names), where (a known venue, or a new one in a city), and when (a day on the calendar, a start time
 * if known). The server reuses a gig that matches the same venue and night, so adding a gig someone
 * else already added is harmless.
 */
export function AddGigForm({
  initialAct,
  onAdded,
  onCancel,
}: {
  initialAct?: string;
  onAdded: (gig: GigSummary) => void;
  onCancel: () => void;
}) {
  const { colors } = useTheme();
  const today = ukDate(new Date());
  const [acts, setActs] = useState<ActChoice[]>(() =>
    initialAct ? addAct([], { id: null, name: initialAct }) : [],
  );
  const [actText, setActText] = useState('');
  const [venue, setVenue] = useState<VenueChoice | null>(null);
  const [venueText, setVenueText] = useState('');
  const [newVenueName, setNewVenueName] = useState<string | null>(null);
  const [cityText, setCityText] = useState('');
  const [date, setDate] = useState(today);
  const [month, setMonth] = useState<Month>(() => monthOf(new Date()));
  const [time, setTime] = useState('');
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const actQuery = useDebounced(actText.trim(), 250);
  const venueQuery = useDebounced(venueText.trim(), 250);
  const cityQuery = useDebounced(cityText.trim(), 250);
  const artists = useListArtists(
    { q: actQuery, limit: 5 },
    { query: { enabled: actQuery.length >= 2 } },
  );
  const venues = useSearchVenues({ q: venueQuery }, { query: { enabled: venueQuery.length >= 2 } });
  const cities = useSearchCities({ q: cityQuery }, { query: { enabled: cityQuery.length >= 2 } });

  const [year, monthNumber, day] = today.split('-').map(Number) as [number, number, number];
  const yesterday = isoDate(year, monthNumber, day - 1);
  const timeOk = time.trim() === '' || parseTime(time) !== null;
  const ready = acts.length > 0 && venue !== null && timeOk && !sending;

  const pickAct = (act: ActChoice) => {
    setActs((current) => addAct(current, act));
    setActText('');
  };

  const submit = async () => {
    if (!venue) {
      return;
    }
    setSending(true);
    setProblem(null);
    try {
      const added = await addGig({
        artistIds: acts.filter((a) => a.id !== null).map((a) => a.id as string),
        artistNames: acts.filter((a) => a.id === null).map((a) => a.name),
        ...(venue.kind === 'known'
          ? { venueId: venue.id }
          : { newVenue: { name: venue.name, cityId: venue.cityId } }),
        date,
        ...(parseTime(time) ? { time: parseTime(time) } : {}),
      });
      onAdded(added.item);
    } catch (error) {
      setProblem(
        error instanceof ApiError ? error.message : 'The gig could not be added. Try again.',
      );
    } finally {
      setSending(false);
    }
  };

  return (
    <View style={[styles.form, { backgroundColor: colors.surfaceRaised }]} testID="add-gig-form">
      <Text style={styles.heading}>Add the gig</Text>
      <Text variant="caption" muted>
        For gigs no ticket site lists: pub shows, small rooms, last month&apos;s gig.
      </Text>
      <PastGigSearch initialArtist={initialAct} onAdded={onAdded} />
      <Text variant="label" muted>
        OR ADD IT BY HAND
      </Text>

      <Text variant="label" muted>
        WHO PLAYED?
      </Text>
      <View style={styles.wrap}>
        {acts.map((act) => (
          <Pressable
            key={act.id ?? act.name}
            accessibilityRole="button"
            accessibilityLabel={`Remove ${act.name}`}
            onPress={() => setActs((current) => current.filter((a) => a !== act))}
            style={[styles.pill, { backgroundColor: colors.accent }]}
          >
            <Text variant="caption" style={{ color: colors.onAccent, fontWeight: '700' }}>
              {act.name}
            </Text>
            <Ionicons name="close" size={14} color={colors.onAccent} />
          </Pressable>
        ))}
      </View>
      <TextField
        value={actText}
        onChangeText={setActText}
        placeholder={acts.length === 0 ? 'Headliner' : 'Another act (optional)'}
        accessibilityLabel="Act name"
        autoCorrect={false}
        onSubmitEditing={() => pickAct({ id: null, name: actText })}
      />
      {actQuery.length >= 2 ? (
        <View style={styles.wrap}>
          {(artists.data?.items ?? []).map((a) => (
            <Chip
              key={a.id}
              label={a.name}
              selected={false}
              onPress={() => pickAct({ id: a.id, name: a.name })}
            />
          ))}
          <Chip
            label={`+ New act “${actText.trim()}”`}
            selected={false}
            onPress={() => pickAct({ id: null, name: actText })}
          />
        </View>
      ) : null}

      <Text variant="label" muted>
        WHERE?
      </Text>
      {venue ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Change venue ${venue.name}`}
          onPress={() => {
            setVenue(null);
            setNewVenueName(null);
          }}
          style={[styles.pill, styles.selfStart, { backgroundColor: colors.accent }]}
        >
          <Ionicons name="location" size={14} color={colors.onAccent} />
          <Text variant="caption" style={{ color: colors.onAccent, fontWeight: '700' }}>
            {venue.name}
            {venue.city ? `, ${venue.city}` : ''}
          </Text>
          <Ionicons name="close" size={14} color={colors.onAccent} />
        </Pressable>
      ) : newVenueName !== null ? (
        <>
          <Text variant="caption">New venue “{newVenueName}”: which town or city is it in?</Text>
          <TextField
            value={cityText}
            onChangeText={setCityText}
            placeholder="Town or city"
            accessibilityLabel="Venue city"
          />
          <View style={styles.wrap}>
            {(cities.data ?? []).slice(0, 5).map((c) => (
              <Chip
                key={c.id}
                label={c.region ? `${c.name}, ${c.region}` : c.name}
                selected={false}
                onPress={() =>
                  setVenue({ kind: 'new', name: newVenueName, cityId: c.id, city: c.name })
                }
              />
            ))}
          </View>
        </>
      ) : (
        <>
          <TextField
            value={venueText}
            onChangeText={setVenueText}
            placeholder="Venue name"
            accessibilityLabel="Venue name"
            autoCorrect={false}
          />
          {venueQuery.length >= 2 ? (
            <View style={styles.wrap}>
              {(venues.data ?? []).slice(0, 6).map((v) => (
                <Chip
                  key={v.id}
                  label={v.city ? `${v.name}, ${v.city}` : v.name}
                  selected={false}
                  onPress={() =>
                    setVenue({ kind: 'known', id: v.id, name: v.name, city: v.city ?? null })
                  }
                />
              ))}
              <Chip
                label={`+ New venue “${venueText.trim()}”`}
                selected={false}
                onPress={() => setNewVenueName(venueText.trim().replace(/\s+/g, ' '))}
              />
            </View>
          ) : null}
        </>
      )}

      <Text variant="label" muted>
        WHEN? {formatGigDate(`${date}T12:00:00Z`).toUpperCase()}
      </Text>
      <View style={styles.wrap}>
        <Chip label="Today" selected={date === today} onPress={() => setDate(today)} />
        <Chip label="Yesterday" selected={date === yesterday} onPress={() => setDate(yesterday)} />
      </View>
      <MonthCalendar
        month={month}
        onMonthChange={setMonth}
        counts={{}}
        selected={date}
        onSelect={(picked) => picked && setDate(picked)}
        today={today}
        testID="add-gig-date"
      />
      <TextField
        value={time}
        onChangeText={setTime}
        placeholder="Start time, e.g. 20:00 (optional)"
        accessibilityLabel="Start time"
        keyboardType="numbers-and-punctuation"
      />
      {!timeOk ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          Write the time as 20:00.
        </Text>
      ) : null}
      {problem ? (
        <Text variant="caption" style={{ color: colors.danger }} accessibilityRole="alert">
          {problem}
        </Text>
      ) : null}
      <View style={styles.wrap}>
        <Button
          title={sending ? 'Adding…' : 'Add gig'}
          disabled={!ready}
          onPress={() => void submit()}
          testID="add-gig-submit"
        />
        <Button title="Cancel" variant="secondary" onPress={onCancel} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  form: { borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  heading: { fontWeight: '700', fontSize: 17 },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  selfStart: { alignSelf: 'flex-start' },
});
