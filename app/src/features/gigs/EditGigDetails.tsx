import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import {
  getGetGigQueryKey,
  useEditGig,
  useListArtists,
  useSearchVenues,
} from '@/api/generated/catalog/catalog';
import type { EditGigRequest, GigDetail } from '@/api/generated/model';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { TextField } from '@/components/ui/TextField';
import { parseTime } from '@/features/upload/gigPicker';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { isGigList } from './AttendanceButtons';

/** Whether the viewer may fix this gig's details: they are Going or went, and it is on. */
export function canEditGig(detail: GigDetail): boolean {
  const mark = detail.gig.myAttendance;
  return (mark === 'GOING' || mark === 'WENT') && detail.gig.status !== 'CANCELLED';
}

/** "2026-10-03" for a valid calendar date, else null. */
export function parseDate(text: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text.trim());
  if (!match) return null;
  const [y, m, d] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d
    ? match[0]
    : null;
}

/**
 * "Fix the details" on a gig page: anyone Going or who went adds an act to the bill (a
 * support act the listing missed, say) and fixes the start time; the fan who added the gig also changes
 * its date and venue. The server logs every edit with its author. Folded until opened.
 */
export function EditGigDetails({ detail }: { detail: GigDetail }) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const edit = useEditGig();
  const [open, setOpen] = useState(false);
  const [actText, setActText] = useState('');
  const [time, setTime] = useState('');
  const [date, setDate] = useState('');
  const [venueText, setVenueText] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const actQuery = useDebounced(actText.trim(), 250);
  const venueQuery = useDebounced(venueText.trim(), 250);
  const artists = useListArtists(
    { q: actQuery, limit: 5 },
    { query: { enabled: open && actQuery.length >= 2 } },
  );
  const venues = useSearchVenues(
    { q: venueQuery },
    { query: { enabled: open && detail.addedByMe && venueQuery.length >= 2 } },
  );

  if (!canEditGig(detail)) return null;
  if (!open) {
    return (
      <Button
        title="Fix the details"
        variant="secondary"
        size="small"
        onPress={() => setOpen(true)}
        testID="edit-gig-open"
      />
    );
  }

  const onBill = new Set(detail.lineup.map((act) => act.id));

  async function send(change: EditGigRequest, said: string) {
    setProblem(null);
    setDone(null);
    try {
      const saved = await edit.mutateAsync({ id: detail.gig.id, data: change });
      client.setQueriesData<GigDetail>({ queryKey: getGetGigQueryKey(detail.gig.id) }, (old) =>
        old ? { ...saved, gig: { ...saved.gig, distanceKm: old.gig.distanceKm } } : old,
      );
      void client.invalidateQueries({ predicate: (query) => isGigList(query.queryKey) });
      setDone(said);
      setActText('');
      setTime('');
      setDate('');
      setVenueText('');
    } catch (error) {
      setProblem(
        error instanceof ApiError && error.status === 403
          ? 'You can’t change that on this gig.'
          : 'That didn’t save. Check it and try again.',
      );
    }
  }

  const parsedTime = parseTime(time);
  const parsedDate = parseDate(date);
  return (
    <View style={styles.box} testID="edit-gig">
      <Text variant="label" muted>
        FIX THE DETAILS
      </Text>
      <Text variant="caption" muted>
        Your changes are saved with your name, so a wrong one can be put right.
      </Text>

      <TextField
        value={actText}
        onChangeText={setActText}
        placeholder="Add an act (a support act, say)"
        accessibilityLabel="Add an act"
        autoCorrect={false}
      />
      {actQuery.length >= 2 ? (
        <View style={styles.row}>
          {(artists.data?.items ?? [])
            .filter((artist) => !onBill.has(artist.id))
            .map((artist) => (
              <Chip
                key={artist.id}
                label={`+ ${artist.name}`}
                selected={false}
                disabled={edit.isPending}
                onPress={() =>
                  void send({ addArtistIds: [artist.id] }, `${artist.name} added to the bill.`)
                }
              />
            ))}
          <Chip
            label={`+ New act “${actText.trim()}”`}
            selected={false}
            disabled={edit.isPending}
            onPress={() =>
              void send(
                { addArtistNames: [actText.trim()] },
                `${actText.trim()} added to the bill.`,
              )
            }
          />
        </View>
      ) : null}

      <View style={styles.inline}>
        <View style={styles.grow}>
          <TextField
            value={time}
            onChangeText={setTime}
            placeholder="Start time, e.g. 19:30"
            accessibilityLabel="Start time"
            keyboardType="numbers-and-punctuation"
          />
        </View>
        <Button
          title="Save time"
          size="small"
          disabled={parsedTime === null || edit.isPending}
          onPress={() => void send({ startTime: parsedTime }, `Start time set to ${parsedTime}.`)}
        />
      </View>

      {detail.addedByMe ? (
        <>
          <Text variant="caption" muted>
            You added this gig, so you can also change its date and venue.
          </Text>
          <View style={styles.inline}>
            <View style={styles.grow}>
              <TextField
                value={date}
                onChangeText={setDate}
                placeholder="Date, e.g. 2026-10-02"
                accessibilityLabel="Date"
                keyboardType="numbers-and-punctuation"
              />
            </View>
            <Button
              title="Save date"
              size="small"
              disabled={parsedDate === null || edit.isPending}
              onPress={() => void send({ date: parsedDate }, `Date set to ${parsedDate}.`)}
            />
          </View>
          <TextField
            value={venueText}
            onChangeText={setVenueText}
            placeholder="Move to another venue"
            accessibilityLabel="Venue"
            autoCorrect={false}
          />
          {venueQuery.length >= 2 ? (
            <View style={styles.row}>
              {(venues.data ?? [])
                .filter((venue) => venue.id !== detail.gig.venue.id)
                .slice(0, 6)
                .map((venue) => (
                  <Chip
                    key={venue.id}
                    label={venue.city ? `${venue.name}, ${venue.city}` : venue.name}
                    selected={false}
                    disabled={edit.isPending}
                    onPress={() => void send({ venueId: venue.id }, `Moved to ${venue.name}.`)}
                  />
                ))}
            </View>
          ) : null}
        </>
      ) : null}

      {done ? (
        <Text variant="caption" muted testID="edit-gig-done">
          {done}
        </Text>
      ) : null}
      {problem ? (
        <Text variant="caption" style={{ color: colors.danger }} testID="edit-gig-error">
          {problem}
        </Text>
      ) : null}
      <Button title="Done" variant="secondary" size="small" onPress={() => setOpen(false)} />
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  row: { flexDirection: 'row', flexWrap: 'wrap' },
  inline: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  grow: { flex: 1 },
});
