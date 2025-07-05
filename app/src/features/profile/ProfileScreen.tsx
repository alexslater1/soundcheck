import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getGetUserByHandleQueryKey, useGetUserByHandle } from '@/api/generated/me/me';
import { useListUserPosts } from '@/api/generated/posts/posts';
import { blockUser, unblockUser } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { PostGrid } from '@/features/posts/PostGrid';
import { GigStatsSection } from '@/features/stats/GigStatsSection';
import { spacing } from '@/theme/tokens';

import { FollowUserButton } from './FollowUserButton';
import { ProfileHeader } from './ProfileHeader';

/**
 * Someone's profile (`/u/{handle}`): header with counts, follow, block in the More menu, their clips,
 * and their gig stats when they share them.
 */
export function ProfileScreen({ handle }: { handle: string }) {
  const client = useQueryClient();
  const myId = useAuthStore((state) => state.me?.id);
  const profile = useGetUserByHandle(handle);
  const [menuOpen, setMenuOpen] = useState(false);
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
      <ScrollView contentContainerStyle={styles.gap}>
        <ProfileHeader
          handle={user.handle}
          displayName={user.displayName}
          avatarUrl={user.avatarUrl}
          bio={user.bio}
          detail={user.homeCityName}
          counts={user.counts}
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
                  key={`${user.id}-${user.isFollowing}`}
                  userId={user.id}
                  handle={user.handle}
                  isFollowing={user.isFollowing}
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
        ) : (
          <>
            <UserPosts userId={user.id} />
            {user.contentVisible ? <GigStatsSection userId={user.id} own={mine} /> : null}
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
});
