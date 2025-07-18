import { useLocalSearchParams } from 'expo-router';

import { EditPostScreen } from '@/features/editPost/EditPostScreen';

export default function EditPostRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <EditPostScreen id={id} />;
}
