import type { NotificationView } from '@/api/generated/model';

/** The sentence shown for a notification. */
export function notificationText(notification: NotificationView): string {
  const who = notification.actor ? `@${notification.actor.handle}` : 'Someone';
  switch (notification.type) {
    case 'NEW_FOLLOWER':
      return `${who} started following you`;
    case 'POST_LIKED': {
      // Likes are collapsed into one entry per post: say how many others there are.
      const others = (notification.likeCount ?? 1) - 1;
      if (others <= 0) {
        return `${who} liked your clip`;
      }
      return `${who} and ${others} ${others === 1 ? 'other' : 'others'} liked your clip`;
    }
    case 'POST_COMMENTED':
      return `${who} commented on your clip`;
    case 'POST_READY':
      return 'Your clip is ready';
    case 'POST_FAILED':
      return 'Your clip could not be processed';
  }
}

/** Where tapping a notification goes: the post it is about, or the follower's profile. */
export function notificationTarget(
  notification: NotificationView,
):
  | { pathname: '/post/[id]'; params: { id: string } }
  | { pathname: '/u/[handle]'; params: { handle: string } }
  | null {
  if (notification.postId) {
    return { pathname: '/post/[id]', params: { id: notification.postId } };
  }
  if (notification.actor) {
    return { pathname: '/u/[handle]', params: { handle: notification.actor.handle } };
  }
  return null;
}
