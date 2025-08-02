import { useQueryClient } from '@tanstack/react-query';
import { Link, useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useListMyPosts } from '@/api/generated/posts/posts';
import { IconButton } from '@/components/ui/IconButton';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { pollDelayForAge } from '@/features/player/mediaPolling';
import { PostGrid } from '@/features/posts/PostGrid';
import { GigStatsSection } from '@/features/stats/GigStatsSection';
import { refreshOwnGigs } from '@/features/stats/refreshOwnGigs';
import { postOnItsWay } from '@/features/upload/labels';
import { useUploadQueue } from '@/features/upload/uploadQueue';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget } from '@/theme/tokens';

import { FollowedArtists } from './FollowedArtists';
import { MyGigsCalendar } from './MyGigsCalendar';
import { ProfileHeader } from './ProfileHeader';
import { ProfileTabs, type ProfileTab } from './ProfileTabs';
import { useChangeAvatar } from './useChangeAvatar';

/**
 * Own profile (Me tab): the header with counts, a Settings gear, and the photo, bio, city and genres
 * each opening what changes them. Under it three tabs: Posts (every post of one's own, including the
 * ones still processing or failed, each with a badge), My gigs (every gig the user marked), Artists
 * (the artists and venues followed) and Stats (with who else can see them).
 */
export function MeScreen() {
  const me = useAuthStore((state) => state.me);
  const reloadMe = useAuthStore((state) => state.reloadMe);
  const router = useRouter();
  const client = useQueryClient();
  const { colors } = useTheme();
  const [tab, setTab] = useState<ProfileTab>('posts');
  const photo = useChangeAvatar();
  // When the grid first showed something still processing; cleared when nothing is. The polling
  // schedule runs from this moment, not from when the tab was first opened.
  const waitingSince = useRef<number | null>(null);
  const posts = useListMyPosts(
    { limit: 30 },
    {
      query: {
        // While something is still processing, keep looking so its badge clears by itself.
        refetchInterval: (query) => {
          const waiting =
            query.state.status !== 'error' &&
            query.state.data?.items.some((post) => post.status === 'PENDING_MEDIA');
          if (!waiting) {
            waitingSince.current = null;
            return false;
          }
          waitingSince.current ??= Date.now();
          return pollDelayForAge(Date.now() - waitingSince.current);
        },
      },
    },
  );
  const refetch = posts.refetch;
  const myId = me?.id;
  // A background reload that fails (offline, or the session just ended) keeps the profile on screen;
  // it must not escape as an unhandled rejection.
  const reloadProfile = useCallback(() => {
    reloadMe().catch(() => undefined);
  }, [reloadMe]);
  // Coming back from the Create tab, a post or a gig page: counts, the grid, the gigs marked and the
  // stats may all have changed. The first focus is the first load: nothing to ask again.
  const focusedBefore = useRef(false);
  useFocusEffect(
    useCallback(() => {
      reloadProfile();
      void refetch();
      if (focusedBefore.current && myId) {
        void refreshOwnGigs(client, myId);
      }
      focusedBefore.current = true;
    }, [reloadProfile, refetch, client, myId]),
  );

  // A post going live marks its gig Went, and deleting the last one of a gig removes that:
  // when the set of live posts changes while Me is open, the stats and the calendar follow.
  const live = posts.data?.items
    .filter((post) => post.status === 'PUBLISHED')
    .map((post) => post.id)
    .join(',');
  const liveBefore = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (live === undefined) return;
    if (liveBefore.current !== undefined && liveBefore.current !== live && myId) {
      void refreshOwnGigs(client, myId);
    }
    liveBefore.current = live;
  }, [live, client, myId]);

  // Posts created from this device: the moment an upload becomes a post (it happens in the background,
  // usually just after arriving here from the Create tab) the grid is fetched again.
  const posted = useUploadQueue((state) =>
    state.entries
      .filter((entry) => entry.ownerId === myId && entry.postId)
      .map((entry) => entry.postId)
      .join(','),
  );
  const onTheirWay = useUploadQueue(
    (state) =>
      state.entries.filter((entry) => entry.ownerId === myId && postOnItsWay(entry)).length,
  );
  useEffect(() => {
    if (posted !== '') {
      void refetch();
      reloadProfile();
    }
  }, [posted, refetch, reloadProfile]);

  if (!me) {
    return (
      <Screen>
        <View style={styles.gap}>
          <Skeleton height={160} />
          <Skeleton height={200} />
        </View>
      </Screen>
    );
  }
  const genres = me.genres.length;
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap}>
        <ProfileHeader
          handle={me.handle}
          displayName={me.displayName}
          avatarUrl={me.avatarUrl}
          bio={me.bio}
          counts={me.counts}
          onEditPhoto={() => void photo.change()}
          photoBusy={photo.saving}
          onEditBio={() => router.push('/settings/profile')}
          onArtistsPress={() => setTab('artists')}
          corner={
            <IconButton
              icon="settings-outline"
              accessibilityLabel="Settings"
              onPress={() => router.push('/settings')}
              testID="me-settings"
            />
          }
          detail={
            <View style={styles.detail}>
              <Link href="/settings/location" testID="me-city" style={styles.detailLink}>
                <Text variant="caption" muted>
                  {me.homeCity ? me.homeCity.name : 'Add your city'}
                </Text>
              </Link>
              <Text variant="caption" muted>
                ·
              </Text>
              <Link href="/settings/genres" testID="me-genres" style={styles.detailLink}>
                <Text variant="caption" muted>
                  {genres === 1 ? '1 genre' : `${genres} genres`}
                </Text>
              </Link>
            </View>
          }
        />
        {photo.error ? (
          <Text variant="caption" style={{ color: colors.danger }} testID="me-photo-error">
            {photo.error}
          </Text>
        ) : null}
        {onTheirWay > 0 ? (
          <Text muted testID="me-uploading">
            {onTheirWay === 1
              ? 'Your clip is uploading and will appear here when it has been posted.'
              : `${onTheirWay} clips are uploading and will appear here when they have been posted.`}
          </Text>
        ) : null}
        <ProfileTabs value={tab} onChange={setTab} gigsLabel="My gigs" />
        {tab === 'posts' ? (
          posts.isPending ? (
            <Skeleton height={200} />
          ) : !posts.data ? (
            <StateMessage title="Could not load your clips" onRetry={() => void posts.refetch()} />
          ) : posts.data.items.length === 0 && onTheirWay > 0 ? null : posts.data.items.length ===
            0 ? (
            <Text muted testID="me-no-posts">
              No clips yet. Share one from the Create tab.
            </Text>
          ) : (
            <PostGrid posts={posts.data.items} />
          )
        ) : tab === 'gigs' ? (
          <MyGigsCalendar />
        ) : tab === 'artists' ? (
          <FollowedArtists userId={me.id} own />
        ) : (
          <>
            <GigStatsSection userId={me.id} own />
            <Link href="/settings/privacy" testID="me-stats-privacy">
              <Text variant="caption" muted>
                {me.profileVisibility === 'PRIVATE'
                  ? 'Your profile is private: only your followers see your clips and stats. Change →'
                  : 'Your profile is public: anyone signed in sees your clips and stats. Change →'}
              </Text>
            </Link>
          </>
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.md, paddingBottom: spacing.xl },
  detail: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  // A comfortable target for a one-word link.
  detailLink: { minHeight: touchTarget / 2, paddingVertical: spacing.xs },
});
