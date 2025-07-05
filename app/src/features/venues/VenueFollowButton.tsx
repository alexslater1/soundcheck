import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { useFollowVenue, useUnfollowVenue } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';

/** Query keys that show a venue's follow state (`/venues/{id}`). */
const showsVenues = (key: readonly unknown[]) =>
  typeof key[0] === 'string' && /\/venues(\/|$)/.test(key[0]);

/**
 * Follow / Following for a venue: a followed venue's new gigs arrive as an alert in Activity.
 * Optimistic like the artist button: the label flips at once and flips back if the request fails.
 */
export function VenueFollowButton({
  venueId,
  venueName,
  isFollowing,
}: {
  venueId: string;
  venueName: string;
  isFollowing: boolean;
}) {
  const client = useQueryClient();
  const follow = useFollowVenue();
  const unfollow = useUnfollowVenue();
  // null = show what the server said; a boolean = our optimistic answer while it catches up.
  const [optimistic, setOptimistic] = useState<boolean | null>(null);
  const [failed, setFailed] = useState(false);
  const following = optimistic ?? isFollowing;

  async function toggle() {
    const next = !following;
    setOptimistic(next);
    setFailed(false);
    try {
      await (next ? follow : unfollow).mutateAsync({ id: venueId });
      await client.invalidateQueries({ predicate: (query) => showsVenues(query.queryKey) });
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
          ? `Could not save. Try again: ${following ? 'unfollow' : 'follow'} ${venueName}`
          : following
            ? `Unfollow ${venueName}`
            : `Follow ${venueName}: hear about its new gigs`
      }
      variant={following ? 'secondary' : 'primary'}
      disabled={follow.isPending || unfollow.isPending}
      onPress={() => void toggle()}
    />
  );
}
