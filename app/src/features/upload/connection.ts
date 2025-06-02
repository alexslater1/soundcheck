import { getNetworkStateAsync, NetworkStateType } from 'expo-network';

import type { Connection } from './compressDecision';

/**
 * What the phone is connected through, for the compression decision. Wi-Fi and cable count as
 * "wifi" (fast and usually unmetered); a mobile network as "cellular"; anything else, or an error, as
 * "unknown", which is treated like mobile data.
 */
export async function currentConnection(): Promise<Connection> {
  try {
    const { type } = await getNetworkStateAsync();
    if (type === NetworkStateType.WIFI || type === NetworkStateType.ETHERNET) {
      return 'wifi';
    }
    return type === NetworkStateType.CELLULAR ? 'cellular' : 'unknown';
  } catch {
    return 'unknown';
  }
}
