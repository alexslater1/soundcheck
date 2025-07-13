import { Stack } from 'expo-router';

import { pageStackOptions } from '@/components/ui/PageHeader';
import { useTheme } from '@/theme/ThemeProvider';

/** Onboarding steps: city → genres → artists. */
export default function OnboardingLayout() {
  const { colors } = useTheme();
  return (
    <Stack screenOptions={pageStackOptions(colors)}>
      <Stack.Screen name="index" options={{ headerShown: false }} />
      <Stack.Screen name="city" options={{ title: 'Your city' }} />
      <Stack.Screen name="genres" options={{ title: 'Your genres' }} />
      <Stack.Screen name="artists" options={{ title: 'Artists' }} />
    </Stack>
  );
}
