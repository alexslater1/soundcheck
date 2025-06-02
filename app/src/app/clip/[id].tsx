import { useLocalSearchParams } from 'expo-router';

import { ClipScreen } from '@/features/player/ClipScreen';
import { PlaybackScope } from '@/features/player/PlaybackScope';

export default function ClipRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return (
    <PlaybackScope>
      <ClipScreen id={id} />
    </PlaybackScope>
  );
}
