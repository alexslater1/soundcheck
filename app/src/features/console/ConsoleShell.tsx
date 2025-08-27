import { Link, usePathname } from 'expo-router';
import type { PropsWithChildren } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { Wordmark } from '@/components/ui/Wordmark';
import { useAuthStore } from '@/features/auth/store';
import { env } from '@/lib/env';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { presetRange, RANGE_PRESETS } from './dateRange';
import { environmentLabel } from './environment';
import { CONSOLE_NAV } from './nav';
import { useConsoleRange } from './useConsoleRange';

/**
 * The console's frame: the left nav from `nav.ts`, and a top bar with the date range (kept in
 * the URL), the environment badge and the synthetic-data note. Pages render inside it. The nav's foot
 * names the account and signs out (signing back in as a fan goes home, `nextRoute`); "Back to the app"
 * shows only for an onboarded account, since the bootstrap admin would be sent to onboarding.
 */
export function ConsoleShell({ children }: PropsWithChildren) {
  const { colors } = useTheme();
  const pathname = usePathname();
  const [range, setRange] = useConsoleRange();
  const environment = environmentLabel(env.apiBaseUrl);
  const handle = useAuthStore((s) => s.me?.handle);
  const onboarded = useAuthStore((s) => s.me?.onboarded === true);
  const signOut = useAuthStore((s) => s.signOut);
  return (
    <View style={[styles.root, { backgroundColor: colors.background }]}>
      <View
        role="navigation"
        style={[styles.nav, { backgroundColor: colors.surface, borderRightColor: colors.border }]}
      >
        <View style={styles.brand}>
          <Wordmark />
          <Text variant="label" muted>
            CONSOLE
          </Text>
        </View>
        {CONSOLE_NAV.map((item) => {
          const active =
            item.href === '/console'
              ? pathname === '/console'
              : pathname.startsWith(String(item.href));
          return (
            <Link key={item.key} href={item.href} asChild>
              <Pressable
                accessibilityRole="link"
                accessibilityState={{ selected: active }}
                testID={`console-nav-${item.key}`}
                // One flat object: expo-router's <Link asChild> rejects a style array on web.
                style={StyleSheet.flatten([
                  styles.navItem,
                  active ? { backgroundColor: colors.accentSoft } : null,
                ])}
              >
                <Text
                  style={active ? { color: colors.text, fontWeight: '600' } : null}
                  muted={!active}
                >
                  {item.label}
                </Text>
              </Pressable>
            </Link>
          );
        })}
        <View style={styles.navFooter}>
          <Text variant="caption" muted testID="console-account">
            {`Signed in as @${handle ?? '…'}`}
          </Text>
          <Pressable
            accessibilityRole="button"
            onPress={() => void signOut()}
            testID="console-sign-out"
          >
            <Text variant="caption" style={{ color: colors.accent, fontWeight: '600' }}>
              Sign out
            </Text>
          </Pressable>
          {onboarded ? (
            <Link href="/" asChild>
              <Pressable accessibilityRole="link">
                <Text variant="caption" muted>
                  ← Back to the app
                </Text>
              </Pressable>
            </Link>
          ) : null}
        </View>
      </View>
      <View style={styles.main}>
        <View style={[styles.topBar, { borderBottomColor: colors.border }]}>
          <View style={styles.presets} role="radiogroup" accessibilityLabel="Date range">
            {RANGE_PRESETS.map((p) => {
              const selected = range.preset === p.id;
              return (
                <Pressable
                  key={p.id}
                  role="radio"
                  aria-checked={selected}
                  onPress={() => setRange(presetRange(p.id, new Date()))}
                  testID={`console-range-${p.id}`}
                  style={[
                    styles.preset,
                    { borderColor: colors.border },
                    selected
                      ? { backgroundColor: colors.accent, borderColor: colors.accent }
                      : null,
                  ]}
                >
                  <Text variant="caption" style={selected ? { color: colors.onAccent } : null}>
                    {p.label}
                  </Text>
                </Pressable>
              );
            })}
            {range.preset ? null : (
              <Text variant="caption" muted testID="console-range-custom">
                {`${range.from.toISOString().slice(0, 16).replace('T', ' ')} → ${range.to.toISOString().slice(0, 16).replace('T', ' ')} UTC`}
              </Text>
            )}
          </View>
          <View style={styles.topRight}>
            <Text variant="caption" muted style={styles.note}>
              Seed and simulator accounts are synthetic and tagged as such.
            </Text>
            <View
              testID="console-env"
              style={[
                styles.badge,
                { backgroundColor: environment === 'AWS' ? colors.danger : colors.accent2Soft },
              ]}
            >
              <Text
                variant="label"
                style={{ color: environment === 'AWS' ? '#FFFFFF' : colors.text }}
              >
                {environment}
              </Text>
            </View>
          </View>
        </View>
        <ScrollView contentContainerStyle={styles.content}>{children}</ScrollView>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, flexDirection: 'row', minHeight: '100%' },
  nav: {
    width: 232,
    borderRightWidth: StyleSheet.hairlineWidth,
    padding: spacing.md,
    gap: spacing.xs,
  },
  brand: { gap: spacing.xs, marginBottom: spacing.lg },
  navItem: { paddingVertical: spacing.sm, paddingHorizontal: spacing.sm, borderRadius: radius.sm },
  navFooter: { marginTop: 'auto', paddingTop: spacing.lg, gap: spacing.xs },
  main: { flex: 1, minWidth: 0 },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
    gap: spacing.md,
  },
  presets: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  preset: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 4,
  },
  topRight: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, flexShrink: 1 },
  note: { flexShrink: 1 },
  badge: { borderRadius: radius.sm, paddingHorizontal: spacing.sm, paddingVertical: 2 },
  content: { padding: spacing.lg, gap: spacing.lg, maxWidth: 1440 },
});
