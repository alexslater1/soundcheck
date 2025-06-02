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
 */
export function GigSetlist({ gigId, startsAt }: { gigId: string; startsAt: string }) {
  const { colors } = useTheme();
  // "Now" as the page opened: a render must not read the clock.
  const [openedAt] = useState(() => Date.now());
  const past = Date.parse(startsAt) < openedAt;
  const setlist = useGetGigSetlist<Awaited<ReturnType<typeof getGigSetlist>>, ApiError>(gigId, {
    query: { enabled: past, retry: false, staleTime: 60 * 60 * 1000 },
  });
  if (!past) {
    return null;
  }

  const heading = (
    <Text variant="label" muted style={styles.heading}>
      SETLIST
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
    return (
      <View style={styles.gap} testID="setlist-none">
        {heading}
        <Text muted>
          {setlist.error.code === 'SETLIST_NOT_FOUND'
            ? 'No setlist on setlist.fm for this gig yet. Fans usually add one within a few days.'
            : 'Could not reach setlist.fm just now.'}
        </Text>
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
                        {[song.coverOf ? `${song.coverOf} cover` : null, song.info]
                          .filter(Boolean)
                          .join(' · ')}
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
