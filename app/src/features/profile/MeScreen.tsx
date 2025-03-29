import { Link, useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef } from 'react';
import { ScrollView, StyleSheet } from 'react-native';

import { useListMyPosts } from '@/api/generated/posts/posts';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { pollDelayForAge } from '@/features/player/mediaPolling';
import { PostGrid } from '@/features/posts/PostGrid';
import { postOnItsWay } from '@/features/upload/labels';
import { useUploadQueue } from '@/features/upload/uploadQueue';
import { spacing } from '@/theme/tokens';

import { ProfileHeader } from './ProfileHeader';

/**
 * Own profile (Me tab): header with counts, then every post of one's own, including the ones still
 * processing or failed, each with a badge.
 */
export function MeScreen() {
  const me = useAuthStore((state) => state.me);
  const reloadMe = useAuthStore((state) => state.reloadMe);
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
  // Coming back from the Create tab, or from a post: counts and the grid may have changed.
  useFocusEffect(
    useCallback(() => {
      void reloadMe();
      void refetch();
    }, [reloadMe, refetch]),
  );

  // Posts created from this device: the moment an upload becomes a post (it happens in the background,
  // usually just after arriving here from the Create tab) the grid is fetched again.
  const myId = me?.id;
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
      void reloadMe();
    }
  }, [posted, refetch, reloadMe]);

  if (!me) {
    return (
      <Screen>
        <Text muted>Loading profile…</Text>
      </Screen>
    );
  }
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap}>
        <ProfileHeader
          handle={me.handle}
          displayName={me.displayName}
          avatarUrl={me.avatarUrl}
          bio={me.bio}
          detail={`${me.homeCity ? me.homeCity.name : 'No city yet'} · ${me.genres.length} genres`}
          counts={me.counts}
        />
        {onTheirWay > 0 ? (
          <Text muted testID="me-uploading">
            {onTheirWay === 1
              ? 'Your clip is uploading and will appear here when it has been posted.'
              : `${onTheirWay} clips are uploading and will appear here when they have been posted.`}
          </Text>
        ) : null}
        {posts.isPending ? (
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
        )}
        <Link href="/settings" style={styles.link}>
          <Text muted>Settings →</Text>
        </Link>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.md, paddingBottom: spacing.xl },
  link: { marginTop: spacing.md },
});
