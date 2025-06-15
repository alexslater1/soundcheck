import { Link } from 'expo-router';

import { useListUserStatsGigs } from '@/api/generated/stats/stats';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';

import { statsGigsHref } from './GigStatsSection';

/** At most this many of one's own gigs with an act are counted (one page); more reads "50+ times". */
const COUNTED = 50;

/** "You've seen Keo once", "… 5 times", "… 50+ times". */
export function timesSeenText(name: string, times: number, more: boolean): string {
  if (times === 1 && !more) return `You’ve seen ${name} once`;
  return `You’ve seen ${name} ${times}${more ? '+' : ''} times`;
}

/**
 * The stats on a gig page: with an act picked in the act bar, how many of the viewer's own Went
 * own Went gigs had that act on the bill, linking to them. Nothing when the viewer has never seen it.
 */
export function TimesSeen({ artistId, name }: { artistId: string; name: string }) {
  const { colors } = useTheme();
  const myId = useAuthStore((state) => state.me?.id);
  const gigs = useListUserStatsGigs(
    myId ?? '',
    { artistId, limit: COUNTED },
    { query: { enabled: Boolean(myId) } },
  );
  const times = gigs.data?.items.length ?? 0;
  if (!myId || times === 0) {
    return null;
  }
  return (
    <Link href={statsGigsHref(myId, { artistId, title: name })} testID="times-seen">
      <Text variant="caption" style={{ color: colors.accent2 }}>
        {`${timesSeenText(name, times, Boolean(gigs.data?.nextCursor))} →`}
      </Text>
    </Link>
  );
}
