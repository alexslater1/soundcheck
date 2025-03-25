import { useLocalSearchParams } from 'expo-router';

import { ClipScreen } from '@/features/player/ClipScreen';

export default function ClipRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <ClipScreen id={id} />;
}
