import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getGetUserByHandleQueryKey, useGetUserByHandle } from '@/api/generated/me/me';
import { getListUserPostsQueryKey, useListUserPosts } from '@/api/generated/posts/posts';
import { blockUser, unblockUser } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useRefreshControl } from '@/components/ui/useRefreshControl';
import { useAuthStore } from '@/features/auth/store';
import { PostGrid } from '@/features/posts/PostGrid';
import { GigStatsSection } from '@/features/stats/GigStatsSection';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { FollowedArtists } from './FollowedArtists';
import { FollowUserButton } from './FollowUserButton';
import { ProfileHeader } from './ProfileHeader';
import { ProfileTabs, type ProfileTab } from './ProfileTabs';
import { UserGigs } from './UserGigs';

/**
 * Someone's profile (`/u/{handle}`): header with counts, follow, block in the More menu, then four tabs:
 * their clips, their gigs (Going and Went), the artists and venues they follow, and their gig stats. A
 * private profile the viewer does not follow shows only the header (name, picture, counts) and says it
 * is private; its clips, gigs and stats are not even asked for. Following it sends a request
 * (Requested).
 */
export function ProfileScreen({ handle }: { handle: string }) {
  const client = useQueryClient();
  const { colors } = useTheme();
  const myId = useAuthStore((state) => state.me?.id);
  const profile = useGetUserByHandle(handle);
  const userId = profile.data?.id;
  const refreshControl = useRefreshControl(() =>
    Promise.all([
      profile.refetch(),
      userId ? client.invalidateQueries({ queryKey: getListUserPostsQueryKey(userId) }) : null,
    ]),
  );
  const [menuOpen, setMenuOpen] = useState(false);
  const [tab, setTab] = useState<ProfileTab>('posts');
  const refresh = () => client.invalidateQueries({ queryKey: getGetUserByHandleQueryKey(handle) });

  if (profile.isPending) {
    return (
      <Screen>
        <View testID="profile-loading" style={styles.gap}>
          <Skeleton height={160} />
          <Skeleton height={200} />
        </View>
      </Screen>
    );
  }
  if (!profile.data) {
    return (
      <Screen>
        <StateMessage
          testID="profile-error"
          title="This profile is not available"
          onRetry={() => void profile.refetch()}
        />
      </Screen>
    );
  }
  const user = profile.data;
  const mine = user.id === myId;
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap} refreshControl={refreshControl}>
        <ProfileHeader
          handle={user.handle}
          displayName={user.displayName}
          avatarUrl={user.avatarUrl}
          bio={user.bio}
          detail={user.homeCityName}
          counts={user.counts}
          listsOpen={user.contentVisible}
          onArtistsPress={user.contentVisible ? () => setTab('artists') : undefined}
        >
          {mine ? null : (
            <View style={styles.buttons}>
              {user.isBlocked ? (
                <Button
                  title="Unblock"
                  variant="secondary"
                  onPress={() => void unblockUser(user.id).then(refresh, () => undefined)}
                />
              ) : (
                <FollowUserButton
                  key={`${user.id}-${user.isFollowing}-${user.isRequested}`}
                  userId={user.id}
                  handle={user.handle}
                  isFollowing={user.isFollowing}
                  isRequested={user.isRequested}
                  isPrivate={user.profileVisibility === 'PRIVATE'}
                  onChange={() => void refresh()}
                />
              )}
              <IconButton
                icon="ellipsis-horizontal"
                accessibilityLabel="More actions"
                accessibilityState={{ expanded: menuOpen }}
                onPress={() => setMenuOpen((open) => !open)}
              />
            </View>
          )}
          {menuOpen && !user.isBlocked ? (
            <Button
              title={`Block @${user.handle}`}
              variant="danger"
              onPress={() => {
                setMenuOpen(false);
                void blockUser(user.id).then(refresh, () => undefined);
              }}
            />
          ) : null}
        </ProfileHeader>
        {user.isBlocked ? (
          <Text muted testID="profile-blocked">
            You have blocked this person. You do not see each other&apos;s clips or comments.
          </Text>
        ) : !user.contentVisible ? (
          <View style={styles.private} testID="profile-private">
            <Ionicons name="lock-closed-outline" size={28} color={colors.textMuted} />
            <Text variant="title">This profile is private</Text>
            <Text muted style={styles.privateText} testID="profile-private-detail">
              {user.isRequested
                ? `You asked to follow @${user.handle}. Once they accept, you'll see their clips, their gigs, their gig stats and who they follow.`
                : `Only @${user.handle}'s followers see their clips, their gigs, their gig stats and who they follow. Follow to ask them.`}
            </Text>
          </View>
        ) : (
          <>
            <ProfileTabs value={tab} onChange={setTab} gigsLabel={mine ? 'My gigs' : 'Gigs'} />
            {tab === 'posts' ? (
              <UserPosts userId={user.id} />
            ) : tab === 'gigs' ? (
              <UserGigs userId={user.id} />
            ) : tab === 'artists' ? (
              <FollowedArtists userId={user.id} own={mine} />
            ) : (
              <GigStatsSection userId={user.id} own={mine} />
            )}
          </>
        )}
      </ScrollView>
    </Screen>
  );
}

function UserPosts({ userId }: { userId: string }) {
  const posts = useListUserPosts(userId, { limit: 30 });
  if (posts.isPending) {
    return <Skeleton height={200} />;
  }
  if (!posts.data) {
    return <StateMessage title="Could not load clips" onRetry={() => void posts.refetch()} />;
  }
  if (posts.data.items.length === 0) {
    return (
      <Text muted testID="profile-no-posts">
        No clips yet.
      </Text>
    );
  }
  return <PostGrid posts={posts.data.items} />;
}

const styles = StyleSheet.create({
  gap: { gap: spacing.md, paddingBottom: spacing.xl },
  buttons: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  private: { alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.xl },
  privateText: { textAlign: 'center' },
});
