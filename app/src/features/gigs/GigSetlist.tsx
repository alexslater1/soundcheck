import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import type { SetlistPart } from '@/api/generated/model';
import { useGetGigSetlist, type getGigSetlist } from '@/api/generated/past-gigs/past-gigs';
import type { ApiError } from '@/api/http';
import { Skeleton } from '@/components/ui/Skeleton';
import { Text } from '@/components/ui/Text';
import { isWebUrl, openWebLink } from '@/lib/openLink';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/**
 * The songs played at a past gig, looked up on setlist.fm when the page opens (the API
 * caches it for an hour and never stores it). Upcoming gigs do not ask. setlist.fm's terms want
 * a link back wherever its data is shown. Without a setlist.fm key on the server the section is left out.
 * With an act (the gig page's act bar), that act's own setlist; without, the headliner's
 * or the first act's that setlist.fm has. The heading names whose setlist it is.
 */
export function GigSetlist({
  gigId,
  startsAt,
  artistId,
  actName,
}: {
  gigId: string;
  startsAt: string;
  artistId?: string;
  actName?: string;
}) {
  const { colors } = useTheme();
  // "Now" as the page opened: a render must not read the clock.
  const [openedAt] = useState(() => Date.now());
  const past = Date.parse(startsAt) < openedAt;
  const setlist = useGetGigSetlist<Awaited<ReturnType<typeof getGigSetlist>>, ApiError>(
    gigId,
    artistId ? { artistId } : undefined,
    {
      query: {
        enabled: past,
        // setlist.fm sometimes refuses a call (its rate limit is per key, shared by every user of the
        // API): one quiet retry before saying so. "Not found" and "no key" are answers, not failures.
        retry: (failures, error) => failures < 1 && error.code === 'SETLIST_LOOKUP_FAILED',
        retryDelay: RETRY_AFTER_MS,
        staleTime: 60 * 60 * 1000,
      },
    },
  );
  if (!past) {
    return null;
  }

  const whose = setlist.data?.artist ?? actName;
  const heading = (
    <Text variant="label" muted style={styles.heading}>
      {whose ? `SETLIST · ${whose.toUpperCase()}` : 'SETLIST'}
    </Text>
  );
  if (setlist.isPending) {
    return (
      <View testID="setlist-loading" style={styles.gap}>
        {heading}
        <Skeleton height={18} width="60%" />
        <Skeleton height={18} width="45%" />
        <Skeleton height={18} width="55%" />
      </View>
    );
  }
  if (setlist.error) {
    if (setlist.error.code === 'SETLISTS_UNAVAILABLE') {
      return null;
    }
    const notFound = setlist.error.code === 'SETLIST_NOT_FOUND';
    return (
      <View style={styles.gap} testID="setlist-none">
        {heading}
        <Text muted>
          {!notFound
            ? 'Could not reach setlist.fm just now.'
            : actName
              ? `No setlist for ${actName} at this gig on setlist.fm yet. Fans usually add one within a few days.`
              : 'No setlist on setlist.fm for this gig yet. Fans usually add one within a few days.'}
        </Text>
        {!notFound ? (
          <Text
            accessibilityRole="button"
            style={{ color: colors.accent }}
            onPress={() => void setlist.refetch()}
            testID="setlist-retry"
          >
            Try again
          </Text>
        ) : null}
      </View>
    );
  }

  const { url } = setlist.data;
  const sets = numberSongs(setlist.data.sets);
  return (
    <View style={styles.gap} testID="gig-setlist">
      {heading}
      <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
        {sets.map((part, index) => (
          <View key={index} style={styles.part}>
            {part.title ? (
              <Text variant="label" style={{ color: colors.accent }}>
                {part.title.toUpperCase()}
              </Text>
            ) : null}
            {part.songs.map((song, i) => {
              return (
                <View key={i} style={styles.song}>
                  <Text variant="caption" muted style={styles.number}>
                    {song.number === null ? '▷' : `${song.number}.`}
                  </Text>
                  <View style={styles.title}>
                    <Text muted={song.tape}>{song.name}</Text>
                    {song.coverOf || song.info ? (
                      <Text variant="caption" muted>
                        {songNote(song)}
                      </Text>
                    ) : null}
                  </View>
                </View>
              );
            })}
          </View>
        ))}
      </View>
      {url && isWebUrl(url) ? (
        <Text
          accessibilityRole="link"
          variant="caption"
          style={{ color: colors.accent }}
          onPress={() => void openWebLink(url)}
          testID="setlist-source"
        >
          Setlist from setlist.fm →
        </Text>
      ) : null}
    </View>
  );
}

/** How long to wait before the one retry of a failed lookup. */
const RETRY_AFTER_MS = 1500;

/**
 * The small line under a song: whose song it is when it was a cover, and the setlist's note. setlist.fm
 * writes a bare ">" for a song that runs straight into the next one, which is spelled out here.
 */
export function songNote(song: { coverOf?: string | null; info?: string | null }): string {
  const info = song.info?.trim() === '>' ? 'into the next song' : song.info;
  return [song.coverOf ? `${song.coverOf} cover` : null, info].filter(Boolean).join(' · ');
}

/** Songs numbered through the whole show, encores included; tapes (intros) are not counted. */
export function numberSongs(sets: SetlistPart[]) {
  let count = 0;
  return sets.map((part) => ({
    ...part,
    songs: part.songs.map((song) => ({ ...song, number: song.tape ? null : ++count })),
  }));
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm },
  heading: { marginTop: spacing.md },
  card: {
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.md,
  },
  part: { gap: spacing.xs },
  song: { flexDirection: 'row', gap: spacing.sm, alignItems: 'flex-start' },
  number: { width: 24, textAlign: 'right', paddingTop: 2 },
  title: { flex: 1 },
});
