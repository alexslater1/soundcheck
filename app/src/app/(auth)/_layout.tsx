import { Stack } from 'expo-router';

import { pageStackOptions } from '@/components/ui/PageHeader';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Sign-in has no header: it is the first page, with nothing to go back to. Sign-up shows
 * only the back chevron, because its own heading says "Create account".
 */
export default function AuthLayout() {
  const { colors } = useTheme();
  return (
    <Stack screenOptions={pageStackOptions(colors)}>
      <Stack.Screen name="sign-in" options={{ headerShown: false }} />
      <Stack.Screen name="sign-up" options={{ title: '' }} />
    </Stack>
  );
}
