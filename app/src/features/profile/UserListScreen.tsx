import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getGetUserByHandleQueryKey, useGetUserByHandle } from '@/api/generated/me/me';
import { removeFollower, useListFollowers, useListFollowing } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { spacing, touchTarget } from '@/theme/tokens';

/**
 * Followers of, or people followed by, a user; each row opens that person's profile. On your own
 * Followers list each row also has Remove: they stop following you without being blocked or told,
 * which is how a private profile closes its door to someone already inside.
 */
export function UserListScreen({
  handle,
  kind,
}: {
  handle: string;
  kind: 'followers' | 'following';
}) {
  const profile = useGetUserByHandle(handle);
  const userId = profile.data?.id ?? '';
  const enabled = { query: { enabled: userId !== '' } };
  const followers = useListFollowers(
    userId,
    { limit: 50 },
    kind === 'followers' ? enabled : { query: { enabled: false } },
  );
  const following = useListFollowing(
    userId,
    { limit: 50 },
    kind === 'following' ? enabled : { query: { enabled: false } },
  );
  const list = kind === 'followers' ? followers : following;
  const myId = useAuthStore((state) => state.me?.id);
  const client = useQueryClient();
  const [removed, setRemoved] = useState<ReadonlySet<string>>(new Set());
  const [failed, setFailed] = useState<string | null>(null);
  const canRemove = kind === 'followers' && userId !== '' && userId === myId;

  async function remove(followerId: string) {
    setFailed(null);
    setRemoved((ids) => new Set(ids).add(followerId));
    try {
      await removeFollower(followerId);
      void client.invalidateQueries({ queryKey: getGetUserByHandleQueryKey(handle) });
    } catch {
      setRemoved((ids) => {
        const next = new Set(ids);
        next.delete(followerId);
        return next;
      });
      setFailed(followerId);
    }
  }

  if (profile.isPending || (userId !== '' && list.isPending)) {
    return (
      <Screen>
        <Skeleton height={120} />
      </Screen>
    );
  }
  if (!profile.data || !list.data) {
    return (
      <Screen>
        <StateMessage title="Could not load this list" onRetry={() => void list.refetch()} />
      </Screen>
    );
  }
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap}>
        <Text variant="title">{kind === 'followers' ? 'Followers' : 'Following'}</Text>
        {list.data.items.length === 0 ? (
          <Text muted testID="user-list-empty">
            Nobody yet.
          </Text>
        ) : (
          list.data.items
            .filter((user) => !removed.has(user.id))
            .map((user) => (
              <View key={user.id} style={styles.line}>
                <Link href={{ pathname: '/u/[handle]', params: { handle: user.handle } }} asChild>
                  <Pressable accessibilityRole="link" style={styles.row} testID="user-row">
                    <View>
                      <Text style={styles.bold}>{user.displayName}</Text>
                      <Text muted>@{user.handle}</Text>
                    </View>
                    {user.isFollowing ? (
                      <Text variant="caption" muted>
                        Following
                      </Text>
                    ) : null}
                  </Pressable>
                </Link>
                {canRemove ? (
                  <Button
                    title={failed === user.id ? 'Try again' : 'Remove'}
                    size="small"
                    variant="secondary"
                    accessibilityLabel={`Remove @${user.handle} from your followers`}
                    onPress={() => void remove(user.id)}
                    testID="remove-follower"
                  />
                ) : null}
              </View>
            ))
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  line: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  row: {
    flex: 1,
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  bold: { fontWeight: '700' },
});
