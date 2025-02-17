import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';

/** Create placeholder. */
export default function CreateScreen() {
  return (
    <Screen>
      <Text variant="title">Create</Text>
      <Text muted>Record, compress and upload a clip.</Text>
    </Screen>
  );
}
