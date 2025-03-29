import Ionicons from '@expo/vector-icons/Ionicons';
import { Tabs } from 'expo-router';
import type { ComponentProps } from 'react';

import { useListNotifications } from '@/api/generated/notifications/notifications';
import { useTheme } from '@/theme/ThemeProvider';

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

/** The five tabs. */
export default function TabsLayout() {
  const { colors } = useTheme();
  // The unread count on the Activity tab; refreshed every minute.
  const inbox = useListNotifications({ limit: 1 }, { query: { refetchInterval: 60_000 } });
  const unread = inbox.data?.unreadCount ?? 0;
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.textMuted,
        tabBarStyle: { backgroundColor: colors.surface, borderTopColor: colors.border },
      }}
    >
      {tabs.map((tab) => (
        <Tabs.Screen
          key={tab.name}
          name={tab.name}
          options={{
            title: tab.title,
            tabBarBadge: tab.name === 'activity' && unread > 0 ? unread : undefined,
            tabBarIcon: ({ color, focused, size }) => (
              <Ionicons name={focused ? tab.iconActive : tab.icon} color={color} size={size} />
            ),
          }}
        />
      ))}
    </Tabs>
  );
}
