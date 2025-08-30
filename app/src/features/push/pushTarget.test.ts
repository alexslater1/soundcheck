import { pushTarget } from './pushTarget';

describe('pushTarget', () => {
  it('opens the post a notification is about, and says which entry to mark read', () => {
    expect(pushTarget({ type: 'POST_LIKED', notificationId: 'n1', postId: 'p1' })).toEqual({
      href: { pathname: '/post/[id]', params: { id: 'p1' } },
      notificationId: 'n1',
    });
  });

  it('opens Activity for one about a person, or when the data is missing or odd', () => {
    expect(pushTarget({ type: 'NEW_FOLLOWER', notificationId: 'n2' })).toEqual({
      href: '/activity',
      notificationId: 'n2',
    });
    expect(pushTarget(undefined)).toEqual({ href: '/activity', notificationId: null });
    expect(pushTarget({ postId: 42 })).toEqual({ href: '/activity', notificationId: null });
  });
});
