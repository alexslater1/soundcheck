import { Link } from 'expo-router';
import { useState } from 'react';
import { Image, ScrollView, StyleSheet, View } from 'react-native';

import { PostGrid } from '@/features/posts/PostGrid';
import { useGetGig } from '@/api/generated/catalog/catalog';
import { useListGigPosts } from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { ArtistRow } from '@/features/artists/ArtistRow';
import { useGenreNames } from '@/features/artists/useGenreNames';
import { TimesSeen } from '@/features/stats/TimesSeen';
import { isWebUrl, openWebLink } from '@/lib/openLink';
import { CoverageNote } from '@/features/venues/CoverageNote';
import { useDistanceOrigin } from '@/features/location/deviceLocation';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ActBar, billingLabel } from './ActBar';
import { AttendanceButtons } from './AttendanceButtons';
import { GigSetlist } from './GigSetlist';
import { formatDistance, formatGigTime, formatPrice, statusLabel, ticketLabel } from './format';

/**
 * Gig page: line-up, venue, local date and time, price, attendance, ticket link out, and
 * for a past gig its setlist from setlist.fm. With more than one act, a bar of the acts
 * stays at the top: picking one shows that act's setlist and clips; "Whole night" shows
 * the line-up and every clip.
 */
export function GigDetailScreen({ id }: { id: string }) {
  const { colors } = useTheme();
  const genreNames = useGenreNames();
  const origin = useDistanceOrigin();
  const { data, isPending, refetch } = useGetGig(id, origin.params, {
    query: { enabled: origin.ready },
  });
  const [actId, setActId] = useState<string | null>(null);
  // Whether the page has scrolled under the act bar (it then needs a background).
  const [stuck, setStuck] = useState(false);

  if (isPending) {
    return (
      <Screen>
        <View style={styles.gap} testID="gig-loading">
          <Skeleton height={28} width="70%" />
          <Skeleton height={18} width="50%" />
          <Skeleton height={120} />
        </View>
      </Screen>
    );
  }
  // A failed background refetch keeps the data it already had; the error screen is for having nothing.
  if (!data) {
    return (
      <Screen>
        <StateMessage
          testID="gig-error"
          title="Could not load this gig"
          detail="It may have been removed, or you may be offline."
          onRetry={() => void refetch()}
        />
      </Screen>
    );
  }

  const { gig } = data;
  const status = statusLabel(gig.status);
  const price = formatPrice(gig.priceMin, gig.priceMax, gig.currency);
  const distance = formatDistance(gig.distanceKm);
  const ticketUrl = isWebUrl(gig.ticketUrl) ? gig.ticketUrl : null;
  const setlist = data.sources.find((source) => source.source === 'SETLISTFM');
  const acts = data.lineup;
  const showBar = acts.length > 1;
  const actIndex = acts.findIndex((artist) => artist.id === actId);
  const act = actIndex >= 0 ? acts[actIndex] : undefined;
  return (
    <Screen>
      <ScrollView
        testID="gig-scroll"
        contentContainerStyle={styles.gap}
        stickyHeaderIndices={showBar ? [0] : undefined}
        scrollEventThrottle={32}
        onScroll={(event) => {
          const under = event.nativeEvent.contentOffset.y > 4;
          if (under !== stuck) setStuck(under);
        }}
      >
        {showBar ? (
          <ActBar acts={acts} selected={act?.id ?? null} onSelect={setActId} stuck={stuck} />
        ) : null}
        {gig.imageUrl ? (
          <Image
            source={{ uri: gig.imageUrl }}
            accessibilityIgnoresInvertColors
            style={[styles.image, { backgroundColor: colors.surfaceRaised }]}
          />
        ) : null}
        <Text variant="display">{gig.title}</Text>
        {status ? (
          <Text variant="label" style={{ color: colors.warning }}>
            {status.toUpperCase()}
          </Text>
        ) : null}
        <Text testID="gig-time">{formatGigTime(gig.startsAt)}</Text>
        {gig.userAdded ? (
          <Text variant="caption" muted testID="gig-user-added">
            Added by fans: no ticket site lists this gig, so details may be approximate.
          </Text>
        ) : null}
        <Link href={`/venue/${gig.venue.id}`}>
          <Text style={{ color: colors.accent }}>
            {gig.venue.name}
            {gig.venue.city ? `, ${gig.venue.city}` : ''}
          </Text>
        </Link>
        <CoverageNote note={gig.venue.coverageNote} compact />
        <Text variant="caption" muted>
          {[distance ? `${distance} ${origin.here ? 'away' : 'from your city'}` : null, price]
            .filter(Boolean)
            .join(' · ') || 'Price not listed'}
        </Text>

        <AttendanceButtons detail={data} />

        {ticketUrl ? (
          <Button
            title={ticketLabel(data.sources)}
            accessibilityRole="link"
            onPress={() => void openWebLink(ticketUrl)}
          />
        ) : null}

        {setlist?.url && isWebUrl(setlist.url) ? (
          // setlist.fm's terms: link back wherever its data is used.
          <Text
            accessibilityRole="link"
            style={{ color: colors.accent }}
            onPress={() => void openWebLink(setlist.url as string)}
            testID="gig-setlist-link"
          >
            Setlist on setlist.fm →
          </Text>
        ) : null}

        {act ? (
          <>
            <Text variant="label" muted style={styles.heading} testID="act-billing">
              {billingLabel(actIndex).toUpperCase()}
            </Text>
            <ArtistRow artist={act} genreNames={genreNames} />
            <TimesSeen artistId={act.id} name={act.name} />
            <GigSetlist gigId={id} startsAt={gig.startsAt} artistId={act.id} actName={act.name} />
            <Text variant="label" muted style={styles.heading}>
              {`CLIPS OF ${act.name.toUpperCase()}`}
            </Text>
            <GigClips gigId={id} artistId={act.id} actName={act.name} />
          </>
        ) : (
          <>
            <GigSetlist gigId={id} startsAt={gig.startsAt} />
            <Text variant="label" muted style={styles.heading}>
              LINE-UP
            </Text>
            {acts.map((artist) => (
              <ArtistRow key={artist.id} artist={artist} genreNames={genreNames} />
            ))}
            <Text variant="label" muted style={styles.heading}>
              CLIPS FROM THIS GIG
            </Text>
            <GigClips gigId={id} privateCount={data.privatePostsCount} />
          </>
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  image: { width: '100%', aspectRatio: 16 / 9, borderRadius: radius.md },
  heading: { marginTop: spacing.md },
});

/**
 * Clips people posted from this gig (the way to find someone to follow); with an act, only the clips
 * tagged with it. {@code privateCount}: clips of the whole night from private profiles the viewer may not
 * see: only a count, so a busy night still looks busy, with no previews and no names.
 */
function GigClips({
  gigId,
  artistId,
  actName,
  privateCount = 0,
}: {
  gigId: string;
  artistId?: string;
  actName?: string;
  privateCount?: number;
}) {
  const posts = useListGigPosts(gigId, { limit: 12, ...(artistId ? { artistId } : {}) });
  if (posts.isPending) {
    return <Skeleton height={120} />;
  }
  if (!posts.data) {
    return <StateMessage title="Could not load clips" onRetry={() => void posts.refetch()} />;
  }
  const privateLine =
    privateCount > 0 ? (
      <Text variant="caption" muted testID="gig-private-clips">
        {`+${privateCount} ${privateCount === 1 ? 'clip' : 'clips'} from private profiles`}
      </Text>
    ) : null;
  if (posts.data.items.length === 0) {
    return (
      privateLine ?? (
        <Text muted testID="gig-no-clips">
          {actName
            ? `No clips of ${actName} from this gig yet. Were you there? Share one from the Create tab.`
            : 'No clips yet. Were you there? Share one from the Create tab.'}
        </Text>
      )
    );
  }
  return (
    <>
      <PostGrid posts={posts.data.items} />
      {privateLine}
    </>
  );
}
