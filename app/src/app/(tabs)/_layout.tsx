import Ionicons from '@expo/vector-icons/Ionicons';
import { Tabs } from 'expo-router';
import type { ComponentProps } from 'react';
import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useListNotifications } from '@/api/generated/notifications/notifications';
import { useAuthStore } from '@/features/auth/store';
import { useUploadProblemCount } from '@/features/upload/problems';
import { useTheme } from '@/theme/ThemeProvider';
import { radius } from '@/theme/tokens';
import { fontFamily } from '@/theme/typography';

type IconName = ComponentProps<typeof Ionicons>['name'];

const tabs: { name: string; title: string; icon: IconName; iconActive: IconName }[] = [
  { name: 'index', title: 'Home', icon: 'home-outline', iconActive: 'home' },
  { name: 'gigs', title: 'Gigs', icon: 'calendar-outline', iconActive: 'calendar' },
  { name: 'create', title: 'Create', icon: 'add-circle-outline', iconActive: 'add-circle' },
  {
    name: 'activity',
    title: 'Activity',
    icon: 'notifications-outline',
    iconActive: 'notifications',
  },
  { name: 'me', title: 'Me', icon: 'person-outline', iconActive: 'person' },
];

/**
 * The five tabs. The bar has an explicit height (its labels were cut off on the web) and
 * the open tab's icon sits on a soft accent pill.
 */
export default function TabsLayout() {
  const { colors, typeStyle } = useTheme();
  const insets = useSafeAreaInsets();
  const labelFont = fontFamily(typeStyle, 'label', 600);
  // The unread count on the Activity tab; refreshed every minute.
  const inbox = useListNotifications({ limit: 1 }, { query: { refetchInterval: 60_000 } });
  const unread = inbox.data?.unreadCount ?? 0;
  // Uploads that failed and need the user: a badge on Create, wherever they are.
  const uploadProblems = useUploadProblemCount(useAuthStore((state) => state.me?.id ?? null));
  const badges: Record<string, number> = { activity: unread, create: uploadProblems };
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.textMuted,
        tabBarStyle: {
          backgroundColor: colors.surface,
          borderTopColor: colors.border,
          height: 68 + insets.bottom,
          paddingTop: 4,
          paddingBottom: 6 + insets.bottom,
        },
        tabBarLabelStyle: {
          fontSize: 11,
          lineHeight: 14,
          ...(labelFont ? { fontFamily: labelFont, fontWeight: 'normal' } : { fontWeight: '600' }),
        },
      }}
    >
      {tabs.map((tab) => (
        <Tabs.Screen
          key={tab.name}
          name={tab.name}
          options={{
            title: tab.title,
            tabBarBadge: (badges[tab.name] ?? 0) > 0 ? badges[tab.name] : undefined,
            tabBarIcon: ({ color, focused }) => (
              <View
                style={[
                  styles.pill,
                  { backgroundColor: focused ? colors.accentSoft : 'transparent' },
                ]}
              >
                <Ionicons name={focused ? tab.iconActive : tab.icon} color={color} size={20} />
              </View>
            ),
          }}
        />
      ))}
    </Tabs>
  );
}

const styles = StyleSheet.create({
  // Inside a tab item, React Navigation's icon box is 28 pt and the item has 10 pt of padding: at its
  // default 49 pt bar the label was squeezed to 11 pt and lost its descenders ("Gias", "Activitu"
  // on the web). The bar above is taller (58 pt for the items), and the pill fits the icon box.
  pill: {
    width: 52,
    height: 24,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
