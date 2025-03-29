import { useState } from 'react';

import { followUser, unfollowUser } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';

/**
 * Follow / Following for a user, optimistic like the artist button: the label flips at once and flips
 * back if the request fails. `onChange` lets the screen refresh counts.
 */
export function FollowUserButton({
  userId,
  handle,
  isFollowing,
  onChange,
}: {
  userId: string;
  handle: string;
  isFollowing: boolean;
  onChange?: () => void;
}) {
  const [following, setFollowing] = useState(isFollowing);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  async function toggle() {
    const next = !following;
    setFollowing(next);
    setBusy(true);
    setFailed(false);
    try {
      await (next ? followUser(userId) : unfollowUser(userId));
      onChange?.();
    } catch {
      setFollowing(!next);
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button
      title={failed ? 'Try again' : following ? 'Following' : 'Follow'}
      accessibilityLabel={following ? `Unfollow ${handle}` : `Follow ${handle}`}
      variant={following ? 'secondary' : 'primary'}
      disabled={busy}
      onPress={() => void toggle()}
      testID="follow-user"
    />
  );
}
