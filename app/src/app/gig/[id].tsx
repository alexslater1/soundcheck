import { useLocalSearchParams } from 'expo-router';

import { GigDetailScreen } from '@/features/gigs/GigDetailScreen';

export default function GigRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <GigDetailScreen id={id} />;
}
