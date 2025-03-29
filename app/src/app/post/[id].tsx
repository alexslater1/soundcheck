import { useLocalSearchParams } from 'expo-router';

import { PostScreen } from '@/features/posts/PostScreen';

export default function PostRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <PostScreen id={id} />;
}
