import { Link } from 'expo-router';

import { useCountUserStatsGigs } from '@/api/generated/stats/stats';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';

import { statsGigsHref } from './GigStatsSection';

/** "You've seen Keo once", "… 5 times". */
export function timesSeenText(name: string, times: number): string {
  if (times === 1) return `You’ve seen ${name} once`;
  return `You’ve seen ${name} ${times} times`;
}

/**
 * The stats on a gig page: with an act picked in the act bar, how many of the viewer's own Went
 * gigs had that act on the bill, linking to them. Every one is counted, by the API; nothing shows
 * when the viewer has never seen the act.
 */
export function TimesSeen({ artistId, name }: { artistId: string; name: string }) {
  const { colors } = useTheme();
  const myId = useAuthStore((state) => state.me?.id);
  const count = useCountUserStatsGigs(
    myId ?? '',
    { artistId },
    { query: { enabled: Boolean(myId) } },
  );
  const times = count.data?.gigs ?? 0;
  if (!myId || times === 0) {
    return null;
  }
  return (
    <Link href={statsGigsHref(myId, { artistId, title: name })} testID="times-seen">
      <Text variant="caption" style={{ color: colors.accent2 }}>
        {`${timesSeenText(name, times)} →`}
      </Text>
    </Link>
  );
}
