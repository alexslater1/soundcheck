import { useLocalSearchParams } from 'expo-router';

import { ArtistScreen } from '@/features/artists/ArtistScreen';

export default function ArtistRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <ArtistScreen id={id} />;
}
