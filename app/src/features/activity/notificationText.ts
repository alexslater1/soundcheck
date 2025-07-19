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
    case 'POST_TAGGED':
      return `${who} tagged you in their post`;
    case 'NEW_GIGS':
      return newGigsText(notification);
    case 'FOLLOW_REQUEST':
      return `${who} asked to follow you`;
    case 'FOLLOW_ACCEPTED':
      return `${who} accepted your follow request`;
  }
}

/** "New gig: Keo at The Social" or "3 new gigs: Keo at The Social, …". */
function newGigsText(notification: NotificationView): string {
  const gigs = notification.gigs ?? [];
  const count = notification.gigCount ?? gigs.length;
  const first = gigs[0];
  if (!first) {
    return count === 1 ? 'A new gig for you' : `${count} new gigs for you`;
  }
  const one = `${first.title} at ${first.venueName}`;
  return count <= 1 ? `New gig: ${one}` : `${count} new gigs: ${one}, …`;
}

/**
 * Where tapping a notification goes: a new-gig alert's list of gigs, the post it is about, or the
 * profile of the person who did it (a follower, someone asking to follow, someone who accepted).
 */
export function notificationTarget(
  notification: NotificationView,
):
  | { pathname: '/alerts/[id]'; params: { id: string } }
  | { pathname: '/post/[id]'; params: { id: string } }
  | { pathname: '/u/[handle]'; params: { handle: string } }
  | null {
  if (notification.type === 'NEW_GIGS') {
    return { pathname: '/alerts/[id]', params: { id: notification.id } };
  }
  if (notification.postId) {
    return { pathname: '/post/[id]', params: { id: notification.postId } };
  }
  if (notification.actor) {
    return { pathname: '/u/[handle]', params: { handle: notification.actor.handle } };
  }
  return null;
}
