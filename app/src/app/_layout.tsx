import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack, useRouter, useSegments } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';

import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider, useTheme } from '@/theme/ThemeProvider';

/** Root providers: QueryClient, Theme; redirects by auth/onboarding state. */
export default function RootLayout() {
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { staleTime: 10_000, retry: 1 } } }),
  );
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <RootStack />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

function RootStack() {
  const { scheme, colors } = useTheme();
  const status = useAuthStore((s) => s.status);
  const me = useAuthStore((s) => s.me);
  const bootstrap = useAuthStore((s) => s.bootstrap);
  const segments = useSegments();
  const router = useRouter();

  useEffect(() => {
    void bootstrap();
  }, [bootstrap]);

  // Signed out → auth screens; signed in but not onboarded → onboarding; otherwise the tabs.
  useEffect(() => {
    if (status === 'unknown') return;
    const group = segments[0];
    const inAuth = group === '(auth)';
    const inOnboarding = group === 'onboarding';
    if (status === 'signedOut' && !inAuth) {
      router.replace('/sign-in');
    } else if (status === 'signedIn' && me && !me.onboarded && !inOnboarding) {
      router.replace('/onboarding');
    } else if (status === 'signedIn' && me?.onboarded && (inAuth || inOnboarding)) {
      router.replace('/');
    }
  }, [status, me, segments, router]);

  if (status === 'unknown') {
    return (
      <View
        style={{
          flex: 1,
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: colors.background,
        }}
      >
        <ActivityIndicator color={colors.textMuted} />
      </View>
    );
  }

  return (
    <>
      <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="(auth)" options={{ headerShown: false }} />
        <Stack.Screen name="onboarding" options={{ headerShown: false }} />
        <Stack.Screen name="console" options={{ headerShown: false }} />
        <Stack.Screen name="+not-found" options={{ title: 'Not found' }} />
      </Stack>
    </>
  );
}
