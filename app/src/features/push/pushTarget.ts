/**
 * Where a tapped push notification leads. The server puts the inbox entry's id, its type and,
 * when there is one, the post in the message's data (`PushDispatcher`): a post opens the post, anything
 * else (a follower, a follow request) opens Activity.
 */
export type PushTarget = {
  href: '/activity' | { pathname: '/post/[id]'; params: { id: string } };
  notificationId: string | null;
};

export function pushTarget(data: Record<string, unknown> | null | undefined): PushTarget {
  const text = (key: string) => (typeof data?.[key] === 'string' ? (data[key] as string) : null);
  const postId = text('postId');
  return {
    href: postId ? { pathname: '/post/[id]', params: { id: postId } } : '/activity',
    notificationId: text('notificationId'),
  };
}
