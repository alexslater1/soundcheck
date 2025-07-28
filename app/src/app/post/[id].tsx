import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';

import { handoffFor } from '@/features/player/handoff';
import { PlaybackScope } from '@/features/player/PlaybackScope';
import { PostScreen } from '@/features/posts/PostScreen';

export default function PostRoute() {
  const { id, clip } = useLocalSearchParams<{ id: string; clip?: string }>();
  // Opened from the feed: the post opens on the clip that was in view at the same position.
  const [handoff] = useState(() => handoffFor(id));
  return (
    <PlaybackScope>
      <PostScreen id={id} clipId={clip} startAtMs={handoff?.positionMs} />
    </PlaybackScope>
  );
}
