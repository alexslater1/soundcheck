import { useLocalSearchParams } from 'expo-router';

import { PlaybackScope } from '@/features/player/PlaybackScope';
import { ProfilePostsFeed } from '@/features/profile/ProfilePostsFeed';

/** A profile's posts to swipe through, opened at the one tapped in its grid. */
export default function UserPostsRoute() {
  const { id, start, mine } = useLocalSearchParams<{ id: string; start?: string; mine?: string }>();
  return (
    <PlaybackScope>
      <ProfilePostsFeed userId={id} mine={mine === '1'} startPostId={start} />
    </PlaybackScope>
  );
}
