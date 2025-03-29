import { useLocalSearchParams } from 'expo-router';

import { UserListScreen } from '@/features/profile/UserListScreen';

export default function FollowingRoute() {
  const { handle } = useLocalSearchParams<{ handle: string }>();
  return <UserListScreen handle={handle} kind="following" />;
}
