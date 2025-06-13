import { QueryClientProvider } from '@tanstack/react-query';
import { useFonts } from 'expo-font';
import { Stack, useRouter, useSegments } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { ActivityIndicator, View } from 'react-native';

import { queryClient } from '@/api/queryClient';
import { nextRoute } from '@/features/auth/nextRoute';
import { useTelemetryLifecycle } from '@/features/telemetry/useTelemetryLifecycle';
import { useAuthStore } from '@/features/auth/store';
import { OfflineBanner } from '@/features/network/OfflineBanner';
import { ThemeProvider, useTheme } from '@/theme/ThemeProvider';
import { FONT_FILES } from '@/theme/typography';

/**
 * Root providers: QueryClient, Theme; redirects by auth/onboarding state; the offline
 * banner over every screen.
 */
export default function RootLayout() {
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
  // Space Grotesk and Inter. A font that fails to load leaves the platform font in place,
  // so an error counts as "done" and never blocks the app.
  const [fontsLoaded, fontError] = useFonts(FONT_FILES);

  useEffect(() => {
    void bootstrap();
  }, [bootstrap]);
  useTelemetryLifecycle();

  // Signed out → auth screens; signed in but not onboarded → onboarding; otherwise the tabs.
  // The rules live in nextRoute() (pure, table-tested); this effect only applies its answer.
  useEffect(() => {
    const target = nextRoute({ status, onboarded: me?.onboarded, group: segments[0] });
    if (target) router.replace(target);
  }, [status, me, segments, router]);

  if (status === 'unknown' || (!fontsLoaded && !fontError)) {
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
        <Stack.Screen name="gig/[id]" options={{ title: 'Gig' }} />
        <Stack.Screen name="artist/[id]" options={{ title: 'Artist' }} />
        <Stack.Screen name="venue/[id]" options={{ title: 'Venue' }} />
        <Stack.Screen name="clip/[id]" options={{ title: 'Clip' }} />
        <Stack.Screen name="post/[id]" options={{ title: 'Post' }} />
        <Stack.Screen name="u/[handle]/index" options={{ title: 'Profile' }} />
        <Stack.Screen name="u/[handle]/followers" options={{ title: 'Followers' }} />
        <Stack.Screen name="u/[handle]/following" options={{ title: 'Following' }} />
        <Stack.Screen name="stats/[userId]" options={{ title: 'Gigs' }} />
        <Stack.Screen name="console" options={{ headerShown: false }} />
        {/* Settings has its own stack and header; without this a second "settings" bar sat above it. */}
        <Stack.Screen name="settings" options={{ headerShown: false }} />
        <Stack.Screen name="+not-found" options={{ title: 'Not found' }} />
      </Stack>
      <OfflineBanner />
    </>
  );
}
