import { Screen } from '@/components/ui/Screen';
import { GigsBrowser } from '@/features/gigs/GigsBrowser';

/** Gigs tab: the route stays thin; the screen lives in `features/gigs`. */
export default function GigsScreen() {
  return (
    <Screen>
      <GigsBrowser />
    </Screen>
  );
}
