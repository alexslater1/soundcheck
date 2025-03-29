import { useLocalSearchParams } from 'expo-router';

import { UserListScreen } from '@/features/profile/UserListScreen';

export default function FollowersRoute() {
  const { handle } = useLocalSearchParams<{ handle: string }>();
  return <UserListScreen handle={handle} kind="followers" />;
}
