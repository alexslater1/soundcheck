import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

import { registerPushToken, removePushToken } from '@/api/generated/notifications/notifications';

/** The Android channel FCM messages name (`FcmClient`): heads-up, with the system's sound. */
export const PUSH_CHANNEL = 'activity';

/** The token this phone registered for the signed-in user, kept so sign-out can remove it. */
let registered: string | null = null;

/**
 * Asks to show notifications (Android 13+ asks the user once), gets this phone's FCM token and gives it
 * to the server. Null when the user says no, or the build has no Firebase project
 * (`google-services.json`): the app then works as before, with Activity only.
 */
export async function registerForPush(): Promise<string | null> {
  if (Platform.OS !== 'android') return null;
  try {
    await Notifications.setNotificationChannelAsync(PUSH_CHANNEL, {
      name: 'Activity',
      importance: Notifications.AndroidImportance.HIGH,
    });
    const current = await Notifications.getPermissionsAsync();
    const allowed = current.granted || (await Notifications.requestPermissionsAsync()).granted;
    if (!allowed) return null;
    const { data } = await Notifications.getDevicePushTokenAsync();
    return await sendToken(String(data));
  } catch {
    // No Firebase project in this build, or the server unreachable: the next sign-in tries again.
    return null;
  }
}

/** FCM rotated the token (rare): the server gets the new one. */
export async function sendToken(token: string): Promise<string> {
  await registerPushToken({ token, platform: 'android' });
  registered = token;
  return token;
}

/** Sign-out, while the session still works: this phone stops getting the user's notifications. */
export async function unregisterFromPush(): Promise<void> {
  const token = registered;
  registered = null;
  if (!token) return;
  try {
    await removePushToken({ token });
  } catch {
    // Offline: the token stays until FCM reports it gone or another account registers this phone.
  }
}
