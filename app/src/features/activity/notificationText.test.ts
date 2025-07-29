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
  it('rolls new posts of a followed act or venue into one line, opening the post or the page', () => {
    const one = {
      ...base,
      actor: undefined,
      type: 'NEW_POSTS_ARTIST' as const,
      postId: 'p1',
      subjectId: 'a1',
      subjectName: 'KEO',
      postCount: 1,
    };
    expect(notificationText(one)).toBe('New post from KEO');
    expect(notificationTarget(one)).toEqual({ pathname: '/post/[id]', params: { id: 'p1' } });
    const three = { ...one, postCount: 3 };
    expect(notificationText(three)).toBe('3 new posts from KEO');
    expect(notificationTarget(three)).toEqual({ pathname: '/artist/[id]', params: { id: 'a1' } });
    const venue = {
      ...three,
      type: 'NEW_POSTS_VENUE' as const,
      subjectId: 'v1',
      subjectName: 'Band on the Wall',
    };
    expect(notificationText(venue)).toBe('3 new posts at Band on the Wall');
    expect(notificationTarget(venue)).toEqual({ pathname: '/venue/[id]', params: { id: 'v1' } });
  });

  it('names the actor for follows, likes and comments', () => {
    expect(notificationText(base)).toBe('@fan_one started following you');
    expect(notificationText({ ...base, type: 'POST_COMMENTED', postId: 'p1' })).toBe(
      '@fan_one commented on your clip',
    );
    expect(notificationText({ ...base, type: 'POST_LIKED', postId: 'p1', likeCount: 1 })).toBe(
      '@fan_one liked your clip',
    );
  });

  it('names who tagged you, and opens the post', () => {
    const tagged = { ...base, type: 'POST_TAGGED' as const, postId: 'p9' };
    expect(notificationText(tagged)).toBe('@fan_one tagged you in their post');
    expect(notificationTarget(tagged)).toEqual({ pathname: '/post/[id]', params: { id: 'p9' } });
  });

  it('names the actor for follow requests and accepted requests', () => {
    expect(notificationText({ ...base, type: 'FOLLOW_REQUEST', requestPending: true })).toBe(
      '@fan_one asked to follow you',
    );
    expect(notificationText({ ...base, type: 'FOLLOW_ACCEPTED' })).toBe(
      '@fan_one accepted your follow request',
    );
    expect(notificationTarget({ ...base, type: 'FOLLOW_REQUEST' })).toEqual({
      pathname: '/u/[handle]',
      params: { handle: 'fan_one' },
    });
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
