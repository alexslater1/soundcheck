import { useQueryClient } from '@tanstack/react-query';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  getListNotificationsQueryKey,
  markNotificationsRead,
  useListNotifications,
} from '@/api/generated/notifications/notifications';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { notificationTarget, notificationText } from './notificationText';

/**
 * Activity tab: the notification inbox. It is fetched when the tab gets focus (polling on focus is
 * focus is enough, no push), and "Mark all as read" reads up to the newest entry shown.
 */
export function ActivityScreen() {
  const { colors } = useTheme();
  const router = useRouter();
  const client = useQueryClient();
  const inbox = useListNotifications({ limit: 50 });
  const refetch = inbox.refetch;
  useFocusEffect(
    useCallback(() => {
      void refetch();
    }, [refetch]),
  );

  if (inbox.isPending) {
    return (
      <Screen>
        <View testID="activity-loading" style={styles.gap}>
          <Skeleton height={48} />
          <Skeleton height={48} />
        </View>
      </Screen>
    );
  }
  if (!inbox.data) {
    return (
      <Screen>
        <StateMessage
          testID="activity-error"
          title="Could not load your activity"
          onRetry={() => void inbox.refetch()}
        />
      </Screen>
    );
  }
  const { items, unreadCount } = inbox.data;
  const newest = items[0];
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap}>
        <View style={styles.header}>
          <Text variant="title">Activity</Text>
          {unreadCount > 0 && newest ? (
            <Pressable
              accessibilityRole="button"
              style={styles.markRead}
              onPress={() =>
                void markNotificationsRead({ upTo: newest.id }).then(() =>
                  client.invalidateQueries({ queryKey: getListNotificationsQueryKey() }),
                )
              }
            >
              <Text style={{ color: colors.accent }}>Mark all as read</Text>
            </Pressable>
          ) : null}
        </View>
        {items.length === 0 ? (
          <Text muted testID="activity-empty">
            Nothing yet. Likes, comments and new followers show up here.
          </Text>
        ) : (
          items.map((notification) => {
            const target = notificationTarget(notification);
            return (
              <Pressable
                key={notification.id}
                accessibilityRole={target ? 'link' : 'text'}
                onPress={() => (target ? router.push(target) : undefined)}
                style={[
                  styles.row,
                  {
                    borderColor: colors.border,
                    backgroundColor: notification.read ? 'transparent' : colors.surface,
                  },
                ]}
                testID="notification"
              >
                <Text>{notificationText(notification)}</Text>
                {notification.read ? null : (
                  <View
                    style={[styles.dot, { backgroundColor: colors.accent }]}
                    accessibilityLabel="Unread"
                  />
                )}
              </Pressable>
            );
          })
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  markRead: { minHeight: touchTarget, justifyContent: 'center' },
  row: {
    minHeight: touchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  dot: { width: 10, height: 10, borderRadius: radius.pill },
});
