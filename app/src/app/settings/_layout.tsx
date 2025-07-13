import { Stack } from 'expo-router';

import { pageStackOptions } from '@/components/ui/PageHeader';
import { useTheme } from '@/theme/ThemeProvider';

export default function SettingsLayout() {
  const { colors } = useTheme();
  return (
    <Stack screenOptions={pageStackOptions(colors)}>
      <Stack.Screen name="index" options={{ title: 'Settings' }} />
      <Stack.Screen name="profile" options={{ title: 'Edit profile' }} />
      <Stack.Screen name="account" options={{ title: 'Account' }} />
      <Stack.Screen name="appearance" options={{ title: 'Appearance' }} />
      <Stack.Screen name="genres" options={{ title: 'Your genres' }} />
      <Stack.Screen name="privacy" options={{ title: 'Privacy' }} />
    </Stack>
  );
}
