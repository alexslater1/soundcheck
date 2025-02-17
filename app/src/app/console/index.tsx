import { Link } from 'expo-router';

import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';

/** Console overview stub; analytics, ranking playground and monitors are still to come. */
export default function ConsoleIndex() {
  return (
    <Screen>
      <Text variant="title">Soundcheck console</Text>
      <Text muted>
        Overview, ranking playground, feed inspector, pipeline, ingestion, moderation.
      </Text>
      <Link href="/">
        <Text muted>← Back to the app</Text>
      </Link>
    </Screen>
  );
}
