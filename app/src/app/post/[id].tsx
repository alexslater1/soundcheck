import { useLocalSearchParams } from 'expo-router';

import { PlaybackScope } from '@/features/player/PlaybackScope';
import { PostScreen } from '@/features/posts/PostScreen';

export default function PostRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return (
    <PlaybackScope>
      <PostScreen id={id} />
    </PlaybackScope>
  );
}
