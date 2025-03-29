import { useLocalSearchParams } from 'expo-router';

import { ProfileScreen } from '@/features/profile/ProfileScreen';

export default function ProfileRoute() {
  const { handle } = useLocalSearchParams<{ handle: string }>();
  return <ProfileScreen handle={handle} />;
}
