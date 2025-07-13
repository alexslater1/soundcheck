import { useState } from 'react';

import { followUser, unfollowUser } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';

export type FollowState = 'none' | 'following' | 'requested';

/** The state a tap leads to: following a private profile asks its owner first. */
export function nextFollowState(current: FollowState, isPrivate: boolean): FollowState {
  if (current !== 'none') return 'none'; // unfollow, or cancel the request
  return isPrivate ? 'requested' : 'following';
}

/**
 * Follow / Following / Requested for a user, optimistic like the artist button: the label changes at once
 * and changes back if the request fails. On a private profile, Follow sends a follow request
 * and shows Requested until the owner answers; tapping Requested cancels it. The server's answer
 * has the last word (a profile may have turned public meanwhile). `onChange` lets the screen refresh
 * counts and content.
 */
export function FollowUserButton({
  userId,
  handle,
  isFollowing,
  isRequested,
  isPrivate,
  onChange,
}: {
  userId: string;
  handle: string;
  isFollowing: boolean;
  isRequested: boolean;
  isPrivate: boolean;
  onChange?: () => void;
}) {
  const [state, setState] = useState<FollowState>(
    isFollowing ? 'following' : isRequested ? 'requested' : 'none',
  );
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  async function toggle() {
    const before = state;
    const next = nextFollowState(before, isPrivate);
    setState(next);
    setBusy(true);
    setFailed(false);
    try {
      if (next === 'none') {
        await unfollowUser(userId);
      } else {
        const answer = await followUser(userId);
        setState(answer.state === 'REQUESTED' ? 'requested' : 'following');
      }
      onChange?.();
    } catch {
      setState(before);
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  const title = failed
    ? 'Try again'
    : state === 'following'
      ? 'Following'
      : state === 'requested'
        ? 'Requested'
        : 'Follow';
  const label =
    state === 'following'
      ? `Unfollow ${handle}`
      : state === 'requested'
        ? `Cancel your follow request to ${handle}`
        : isPrivate
          ? `Ask to follow ${handle}`
          : `Follow ${handle}`;
  return (
    <Button
      title={title}
      accessibilityLabel={label}
      variant={state === 'none' ? 'primary' : 'secondary'}
      disabled={busy}
      onPress={() => void toggle()}
      testID="follow-user"
    />
  );
}
