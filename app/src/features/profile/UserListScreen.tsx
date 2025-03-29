import { Link } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { useGetUserByHandle } from '@/api/generated/me/me';
import { useListFollowers, useListFollowing } from '@/api/generated/social/social';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { spacing, touchTarget } from '@/theme/tokens';

/** Followers of, or people followed by, a user; each row opens that person's profile. */
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
          list.data.items.map((user) => (
            <Link
              key={user.id}
              href={{ pathname: '/u/[handle]', params: { handle: user.handle } }}
              asChild
            >
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
          ))
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  row: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  bold: { fontWeight: '700' },
});
