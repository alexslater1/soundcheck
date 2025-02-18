import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

/**
 * Refresh-token storage: Android keeps it in the Keystore-backed secure store; on web the
 * token lives in the HttpOnly `soundcheck_rt` cookie, so nothing is stored here.
 */
const REFRESH_TOKEN_KEY = 'soundcheck.refreshToken';

export const refreshTokenStore = {
  async get(): Promise<string | null> {
    if (Platform.OS === 'web') return null;
    try {
      return await SecureStore.getItemAsync(REFRESH_TOKEN_KEY);
    } catch {
      return null;
    }
  },
  async set(token: string | null): Promise<void> {
    if (Platform.OS === 'web') return;
    try {
      if (token) {
        await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, token);
      } else {
        await SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY);
      }
    } catch {
      // Secure storage failures must not break sign-in; the user will just have to sign in again next launch.
    }
  },
};
