import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { useFollowArtist, useUnfollowArtist } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';

/** Query keys of anything that shows artists (`/artists…`, gig lists and details, search). */
const showsArtists = (key: readonly unknown[]) =>
  typeof key[0] === 'string' && /\/(artists|gigs|search)(\/|$)/.test(key[0]);

/**
 * Follow / Following toggle, optimistic: the label flips at once and flips back if the request fails.
 * The server state (`isFollowing` on every artist summary) is refetched afterwards.
 */
export function FollowButton({
  artistId,
  artistName,
  isFollowing,
  compact = false,
}: {
  artistId: string;
  artistName: string;
  isFollowing: boolean;
  /** Inside a list row: a small outline button, so a list of artists is not a wall of buttons. */
  compact?: boolean;
}) {
  const client = useQueryClient();
  const follow = useFollowArtist();
  const unfollow = useUnfollowArtist();
  // null = show what the server said; a boolean = our optimistic answer while it catches up.
  const [optimistic, setOptimistic] = useState<boolean | null>(null);
  const [failed, setFailed] = useState(false);
  const following = optimistic ?? isFollowing;

  async function toggle() {
    const next = !following;
    setOptimistic(next);
    setFailed(false);
    try {
      await (next ? follow : unfollow).mutateAsync({ id: artistId });
      await client.invalidateQueries({ predicate: (query) => showsArtists(query.queryKey) });
      setOptimistic(null);
    } catch {
      setOptimistic(null); // back to the server's answer
      setFailed(true);
    }
  }

  return (
    <Button
      title={failed ? 'Try again' : following ? 'Following' : 'Follow'}
      accessibilityLabel={
        failed
          ? `Could not save. Try again: ${following ? 'unfollow' : 'follow'} ${artistName}`
          : following
            ? `Unfollow ${artistName}`
            : `Follow ${artistName}`
      }
      variant={following ? 'secondary' : compact ? 'outline' : 'primary'}
      size={compact ? 'small' : 'regular'}
      disabled={follow.isPending || unfollow.isPending}
      onPress={() => void toggle()}
    />
  );
}
