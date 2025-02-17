import { Link } from 'expo-router';

import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';

export default function NotFoundScreen() {
  return (
    <Screen>
      <Text variant="title">Nothing here</Text>
      <Link href="/">
        <Text muted>Go home</Text>
      </Link>
    </Screen>
  );
}
