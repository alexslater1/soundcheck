import { QueryClientProvider } from '@tanstack/react-query';
import { useFonts } from 'expo-font';
import { Stack, useRouter, useSegments } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { ActivityIndicator, View } from 'react-native';

import { queryClient } from '@/api/queryClient';
import { pageStackOptions } from '@/components/ui/PageHeader';
import { nextRoute } from '@/features/auth/nextRoute';
import { useTelemetryLifecycle } from '@/features/telemetry/useTelemetryLifecycle';
import { useAuthStore } from '@/features/auth/store';
import { UnreachableScreen } from '@/features/auth/UnreachableScreen';
import { OfflineBanner } from '@/features/network/OfflineBanner';
import { holdSplash, useSplashGate } from '@/features/splash/splash';
import { ThemeProvider, useTheme } from '@/theme/ThemeProvider';
import { FONT_FILES } from '@/theme/typography';

// The native splash stays up until the app is ready; this must run before the first render.
holdSplash();

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

  // Ready = the session check has answered and the fonts are settled. Until then the native splash
  // covers the screen (at most SPLASH_MAX_MS); the view below is what the web, or a slow start, shows.
  const ready = status !== 'unknown' && (fontsLoaded || !!fontError);
  useSplashGate(ready);

  if (!ready) {
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

  // The session could not be resumed for want of a server: say so, keep the refresh token, retry.
  if (status === 'unreachable') {
    return <UnreachableScreen onRetry={bootstrap} />;
  }

  return (
    <>
      <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
      {/* Every pushed screen gets the page header (PageHeader: no grey bar). */}
      <Stack screenOptions={pageStackOptions(colors)}>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="(auth)" options={{ headerShown: false }} />
        <Stack.Screen name="onboarding" options={{ headerShown: false }} />
        <Stack.Screen name="gig/[id]" options={{ title: 'Gig' }} />
        <Stack.Screen name="artist/[id]" options={{ title: 'Artist' }} />
        <Stack.Screen name="venue/[id]" options={{ title: 'Venue' }} />
        <Stack.Screen name="clip/[id]" options={{ title: 'Clip' }} />
        <Stack.Screen name="post/[id]" options={{ title: 'Post' }} />
        <Stack.Screen name="edit-post/[id]" options={{ title: 'Edit post' }} />
        <Stack.Screen name="u/[handle]/index" options={{ title: 'Profile' }} />
        {/* The lists say "Followers" / "Following" as their own heading, so the header shows only the chevron. */}
        <Stack.Screen name="u/[handle]/followers" options={{ title: '' }} />
        <Stack.Screen name="u/[handle]/following" options={{ title: '' }} />
        <Stack.Screen name="stats/[userId]" options={{ title: 'Gigs' }} />
        <Stack.Screen name="alerts/[id]" options={{ title: 'Alert' }} />
        <Stack.Screen name="console" options={{ headerShown: false }} />
        {/* Settings has its own stack and header; without this a second "settings" bar sat above it. */}
        <Stack.Screen name="settings" options={{ headerShown: false }} />
        <Stack.Screen name="+not-found" options={{ title: 'Not found' }} />
      </Stack>
      <OfflineBanner />
    </>
  );
}
