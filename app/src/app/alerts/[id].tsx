import { useLocalSearchParams } from 'expo-router';

import { AlertGigsScreen } from '@/features/activity/AlertGigsScreen';

export default function AlertRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <AlertGigsScreen id={id} />;
}
