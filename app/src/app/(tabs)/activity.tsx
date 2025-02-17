import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';

/** Activity placeholder. */
export default function ActivityScreen() {
  return (
    <Screen>
      <Text variant="title">Activity</Text>
      <Text muted>Likes, comments and follows.</Text>
    </Screen>
  );
}
