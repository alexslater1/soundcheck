import { Stack } from 'expo-router';

import { useTheme } from '@/theme/ThemeProvider';

/** Onboarding steps: city → genres → artists. */
export default function OnboardingLayout() {
  const { colors } = useTheme();
  return (
    <Stack
      screenOptions={{
        headerStyle: { backgroundColor: colors.surface },
        headerTintColor: colors.text,
        contentStyle: { backgroundColor: colors.background },
      }}
    >
      <Stack.Screen name="index" options={{ headerShown: false }} />
      <Stack.Screen name="city" options={{ title: 'Your city' }} />
      <Stack.Screen name="genres" options={{ title: 'Your genres' }} />
      <Stack.Screen name="artists" options={{ title: 'Artists' }} />
    </Stack>
  );
}
