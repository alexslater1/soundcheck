import { useQueryClient } from '@tanstack/react-query';

import type { Inbox, NotificationView } from '@/api/generated/model';
import {
  getListNotificationsQueryKey,
  markNotificationRead,
} from '@/api/generated/notifications/notifications';

/** An inbox page after one unread entry was opened: that entry read, and one fewer unread. */
export function withEntryRead(inbox: Inbox, id: string): Inbox {
  return {
    ...inbox,
    items: inbox.items.map((n) => (n.id === id ? { ...n, read: true } : n)),
    unreadCount: Math.max(0, inbox.unreadCount - 1),
  };
}

/**
 * Marks a notification read when it is opened. Every cached inbox changes at once, so the row
 * loses its dot and the Activity tab's badge (its own one-item query) counts one fewer before the
 * server answers; then both are read again. An entry already read is left alone.
 */
export function useMarkOpened(): (notification: NotificationView) => void {
  const client = useQueryClient();
  return (notification) => {
    if (notification.read) return;
    const queryKey = getListNotificationsQueryKey();
    client.setQueriesData<Inbox>({ queryKey }, (inbox) =>
      inbox ? withEntryRead(inbox, notification.id) : inbox,
    );
    // A failure is put right by the refetch: the entry comes back unread.
    void markNotificationRead(notification.id)
      .catch(() => undefined)
      .then(() => client.invalidateQueries({ queryKey }));
  };
}
