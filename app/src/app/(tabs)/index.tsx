import { HomeScreen } from '@/features/feed/HomeScreen';
import { PlaybackScope } from '@/features/player/PlaybackScope';

export default function HomeRoute() {
  return (
    <PlaybackScope>
      <HomeScreen />
    </PlaybackScope>
  );
}
