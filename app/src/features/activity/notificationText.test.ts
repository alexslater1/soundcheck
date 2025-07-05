import type { NotificationView } from '@/api/generated/model';

import { notificationTarget, notificationText } from './notificationText';

const base: NotificationView = {
  id: 'n1',
  type: 'NEW_FOLLOWER',
  createdAt: '2026-10-02T10:00:00Z',
  read: false,
  actor: { id: 'u2', handle: 'fan_one', displayName: 'Fan One' },
};

describe('notificationText', () => {
  it('names the actor for follows, likes and comments', () => {
    expect(notificationText(base)).toBe('@fan_one started following you');
    expect(notificationText({ ...base, type: 'POST_COMMENTED', postId: 'p1' })).toBe(
      '@fan_one commented on your clip',
    );
    expect(notificationText({ ...base, type: 'POST_LIKED', postId: 'p1', likeCount: 1 })).toBe(
      '@fan_one liked your clip',
    );
  });

  it('counts the other likers of a collapsed like', () => {
    expect(notificationText({ ...base, type: 'POST_LIKED', postId: 'p1', likeCount: 2 })).toBe(
      '@fan_one and 1 other liked your clip',
    );
    expect(notificationText({ ...base, type: 'POST_LIKED', postId: 'p1', likeCount: 12 })).toBe(
      '@fan_one and 11 others liked your clip',
    );
  });

  it('needs no actor for the clip being ready or failed, and copes with a deleted actor', () => {
    expect(notificationText({ ...base, type: 'POST_READY', actor: undefined, postId: 'p1' })).toBe(
      'Your clip is ready',
    );
    expect(notificationText({ ...base, type: 'POST_FAILED', actor: undefined, postId: 'p1' })).toBe(
      'Your clip could not be processed',
    );
    expect(notificationText({ ...base, actor: undefined })).toBe('Someone started following you');
  });

  it('opens the post when there is one, else the actor’s profile', () => {
    expect(notificationTarget({ ...base, type: 'POST_LIKED', postId: 'p1' })).toEqual({
      pathname: '/post/[id]',
      params: { id: 'p1' },
    });
    expect(notificationTarget(base)).toEqual({
      pathname: '/u/[handle]',
      params: { handle: 'fan_one' },
    });
    expect(notificationTarget({ ...base, actor: undefined })).toBeNull();
  });

  it('names the first new gig of an alert and counts the rest', () => {
    const gig = (title: string, venueName: string) => ({
      gigId: title,
      title,
      startsAt: '2026-11-01T19:30:00Z',
      venueName,
      venueCity: 'London',
      reason: 'ARTIST' as const,
      because: 'Keo',
    });
    const alert = { ...base, type: 'NEW_GIGS' as const, actor: undefined };
    expect(notificationText({ ...alert, gigs: [gig('Keo', 'The Social')], gigCount: 1 })).toBe(
      'New gig: Keo at The Social',
    );
    expect(
      notificationText({
        ...alert,
        gigs: [gig('Keo', 'The Social'), gig('Bleech', 'MOTH Club')],
        gigCount: 3,
      }),
    ).toBe('3 new gigs: Keo at The Social, …');
    expect(notificationText({ ...alert, gigs: [], gigCount: 2 })).toBe('2 new gigs for you');
    expect(notificationTarget({ ...alert, gigs: [gig('Keo', 'The Social')], gigCount: 1 })).toEqual(
      {
        pathname: '/alerts/[id]',
        params: { id: 'n1' },
      },
    );
  });
});
