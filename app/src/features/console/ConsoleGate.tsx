import { useRouter } from 'expo-router';
import { Suspense, type ReactNode } from 'react';
import { ActivityIndicator, Platform, StyleSheet, useWindowDimensions, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { consoleAccess, MIN_CONSOLE_WIDTH } from './access';
// The shell (and through the pages, Recharts) is fetched only when an admin opens the console.
import { LazyConsoleShell } from './lazy';

/**
 * The console layout's guard. Native: a pointer to the desktop. Web: signed out → the root
 * layout sends the user to sign-in and back; not an admin → "for operators", with a way to switch to the
 * admin account, and the pages (which make the admin requests) are never mounted; too narrow → "open on
 * a wider screen"; an admin → the shell.
 */
export function ConsoleGate({ children }: { children: ReactNode }) {
  const status = useAuthStore((s) => s.status);
  const role = useAuthStore((s) => s.me?.role);
  const handle = useAuthStore((s) => s.me?.handle);
  const signOut = useAuthStore((s) => s.signOut);
  const router = useRouter();
  const { width } = useWindowDimensions();
  const access = consoleAccess({ platform: Platform.OS, status, role, width });

  switch (access) {
    case 'native':
      return (
        <Message
          testID="console-native"
          title="Console"
          detail="The console is for a desktop browser: open http://localhost:8088/console."
        />
      );
    case 'loading':
    case 'signedOut':
      return <Loading />;
    case 'forbidden':
      return (
        <Message
          testID="console-forbidden"
          title="This area is for operators"
          detail={`The console is only for Soundcheck's admin account, and you're signed in as @${handle ?? '…'}.`}
        >
          {/* Signed out on a console page, the root layout sends the user to sign-in and then into the
              console (its Overview: this page doesn't mount the console's pages, so the address is /console;
              expo-router would otherwise keep the sub-page's path, /console/users, with nothing showing it). */}
          <Button
            title="Sign out and sign in as admin"
            onPress={() => {
              router.replace('/console');
              void signOut();
            }}
          />
        </Message>
      );
    case 'narrow':
      return (
        <Message
          testID="console-narrow"
          title="Open the console on a wider screen"
          detail={`Its tables and charts need at least ${MIN_CONSOLE_WIDTH} px. Widen the window or use a laptop.`}
        />
      );
    case 'shell':
      return (
        <Suspense fallback={<Loading />}>
          <LazyConsoleShell>{children}</LazyConsoleShell>
        </Suspense>
      );
  }
}

function Message({
  title,
  detail,
  testID,
  children,
}: {
  title: string;
  detail: string;
  testID: string;
  children?: ReactNode;
}) {
  return (
    <Screen>
      <View style={styles.message} testID={testID}>
        <Text variant="title">{title}</Text>
        <Text muted>{detail}</Text>
        {children ? <View style={styles.actions}>{children}</View> : null}
      </View>
    </Screen>
  );
}

function Loading() {
  const { colors } = useTheme();
  return (
    <View style={[styles.loading, { backgroundColor: colors.background }]} testID="console-loading">
      <ActivityIndicator color={colors.textMuted} />
    </View>
  );
}

const styles = StyleSheet.create({
  message: { gap: spacing.sm, paddingTop: spacing.xl },
  actions: { alignItems: 'flex-start', paddingTop: spacing.sm },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
