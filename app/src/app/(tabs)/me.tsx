import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';

/** Me placeholder. */
export default function MeScreen() {
  return (
    <Screen>
      <Text variant="title">Me</Text>
      <Text muted>Your profile and clips.</Text>
    </Screen>
  );
}
