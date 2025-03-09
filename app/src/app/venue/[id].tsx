import { useLocalSearchParams } from 'expo-router';

import { VenueScreen } from '@/features/venues/VenueScreen';

export default function VenueRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <VenueScreen id={id} />;
}
