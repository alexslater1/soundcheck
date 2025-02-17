import { Slot } from 'expo-router';
import { Platform } from 'react-native';

import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';

/**
 * Console shell: web-first and admin-only. Native shows a pointer to the desktop;
 * the admin guard and left nav arrive with the console pages.
 */
export default function ConsoleLayout() {
  if (Platform.OS !== 'web') {
    return (
      <Screen>
        <Text variant="title">Console</Text>
        <Text muted>Open the console on desktop: http://localhost:8088/console</Text>
      </Screen>
    );
  }
  return <Slot />;
}
