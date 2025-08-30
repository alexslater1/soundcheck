import { useQueryClient } from '@tanstack/react-query';
import * as Notifications from 'expo-notifications';
import { useRouter } from 'expo-router';
import { useEffect } from 'react';

import {
  getListNotificationsQueryKey,
  markNotificationRead,
} from '@/api/generated/notifications/notifications';
import { beforeSignOut, useAuthStore } from '@/features/auth/store';

import { pushTarget } from './pushTarget';
import { registerForPush, sendToken, unregisterFromPush } from './pushRegistration';

// While the app is open a push still shows as a banner (no sound: the user is already looking), and
// Activity's badge is read again so the two agree.
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});

// Sign-out removes this phone's token while the session can still authorise it.
beforeSignOut(unregisterFromPush);

/**
 * Push notifications on the phone, mounted once at the root. Once someone is signed in and
 * onboarded, the phone's FCM token is registered for them; a tapped notification opens its post (or
 * Activity) and marks the entry read, as opening it in Activity does.
 */
export function usePushNotifications(): void {
  const userId = useAuthStore((s) => (s.status === 'signedIn' && s.me?.onboarded ? s.me.id : null));
  const client = useQueryClient();
  const router = useRouter();

  useEffect(() => {
    if (!userId) return;
    void registerForPush();
    const rotated = Notifications.addPushTokenListener(({ data }) => {
      void sendToken(String(data)).catch(() => undefined);
    });
    return () => rotated.remove();
  }, [userId]);

  useEffect(() => {
    const queryKey = getListNotificationsQueryKey();
    const open = (response: Notifications.NotificationResponse) => {
      const target = pushTarget(response.notification.request.content.data);
      if (target.notificationId) {
        void markNotificationRead(target.notificationId)
          .catch(() => undefined)
          .then(() => client.invalidateQueries({ queryKey }));
      }
      router.push(target.href);
    };
    // The app was opened by tapping one (cold start), or one is tapped while it runs. The launch tap
    // can only be read asynchronously, so the listener is already listening meanwhile; a response
    // that both report (one delivery: same identifier and date) is opened once.
    let live = true;
    let opened: string | null = null;
    const openOnce = (response: Notifications.NotificationResponse) => {
      const delivery = `${response.notification.request.identifier}@${response.notification.date}`;
      if (delivery === opened) return;
      opened = delivery;
      open(response);
    };
    const tapped = Notifications.addNotificationResponseReceivedListener(openOnce);
    void Notifications.getLastNotificationResponseAsync()
      .then((last) => {
        if (live && last) {
          openOnce(last);
          return Notifications.clearLastNotificationResponseAsync();
        }
      })
      .catch(() => undefined);
    const received = Notifications.addNotificationReceivedListener(() => {
      void client.invalidateQueries({ queryKey });
    });
    return () => {
      live = false;
      tapped.remove();
      received.remove();
    };
  }, [client, router]);
}
