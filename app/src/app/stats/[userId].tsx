import { useLocalSearchParams } from 'expo-router';

import { StatsGigsScreen } from '@/features/stats/StatsGigsScreen';

export default function StatsGigsRoute() {
  const { userId, title, artistId, venueId } = useLocalSearchParams<{
    userId: string;
    title?: string;
    artistId?: string;
    venueId?: string;
  }>();
  return (
    <StatsGigsScreen
      userId={userId}
      title={title ?? 'Gigs'}
      {...(artistId ? { artistId } : {})}
      {...(venueId ? { venueId } : {})}
    />
  );
}
