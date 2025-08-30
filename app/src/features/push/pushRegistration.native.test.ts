import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

import { registerPushToken, removePushToken } from '@/api/generated/notifications/notifications';

import { registerForPush, unregisterFromPush } from './pushRegistration.native';

jest.mock('expo-notifications', () => ({
  AndroidImportance: { HIGH: 4 },
  setNotificationChannelAsync: jest.fn(),
  getPermissionsAsync: jest.fn(),
  requestPermissionsAsync: jest.fn(),
  getDevicePushTokenAsync: jest.fn(),
}));
jest.mock('@/api/generated/notifications/notifications', () => ({
  registerPushToken: jest.fn(),
  removePushToken: jest.fn(),
}));

const mocked = Notifications as jest.Mocked<typeof Notifications>;
const permission = (granted: boolean) => ({ granted }) as never;

describe('push registration on Android', () => {
  beforeEach(async () => {
    // jest-expo runs as iOS; push is Android's (FCM).
    jest.replaceProperty(Platform, 'OS', 'android');
    jest.clearAllMocks();
    await unregisterFromPush(); // forget a token a previous test registered
    jest.clearAllMocks();
  });

  it('asks once, then gives the server the phone’s FCM token; sign-out takes it back', async () => {
    mocked.getPermissionsAsync.mockResolvedValue(permission(false));
    mocked.requestPermissionsAsync.mockResolvedValue(permission(true));
    mocked.getDevicePushTokenAsync.mockResolvedValue({ type: 'android', data: 'fcm-1' });

    await expect(registerForPush()).resolves.toBe('fcm-1');
    expect(mocked.setNotificationChannelAsync).toHaveBeenCalledWith('activity', {
      name: 'Activity',
      importance: 4,
    });
    expect(registerPushToken).toHaveBeenCalledWith({ token: 'fcm-1', platform: 'android' });

    await unregisterFromPush();
    expect(removePushToken).toHaveBeenCalledWith({ token: 'fcm-1' });
    await unregisterFromPush(); // nothing left to remove
    expect(removePushToken).toHaveBeenCalledTimes(1);
  });

  it('registers nothing when the user says no', async () => {
    mocked.getPermissionsAsync.mockResolvedValue(permission(false));
    mocked.requestPermissionsAsync.mockResolvedValue(permission(false));

    await expect(registerForPush()).resolves.toBeNull();
    expect(mocked.requestPermissionsAsync).toHaveBeenCalled();
    expect(mocked.getDevicePushTokenAsync).not.toHaveBeenCalled();
    expect(registerPushToken).not.toHaveBeenCalled();
  });

  it('carries on without push when the build has no Firebase project', async () => {
    mocked.getPermissionsAsync.mockResolvedValue(permission(true));
    mocked.getDevicePushTokenAsync.mockRejectedValue(
      new Error('Default FirebaseApp is not initialized'),
    );

    await expect(registerForPush()).resolves.toBeNull();
    expect(mocked.getDevicePushTokenAsync).toHaveBeenCalled();
    expect(registerPushToken).not.toHaveBeenCalled();
  });
});
