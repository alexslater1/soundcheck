import * as Location from 'expo-location';
import { useEffect, useMemo } from 'react';
import { AppState, Linking } from 'react-native';
import { MMKV } from 'react-native-mmkv';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

import {
  approximate,
  distanceParams,
  FIRST_READ_TIMEOUT_MS,
  STALE_MS,
  type Position,
} from './position';

/** What the phone allows: `unknown` until checked, `undetermined` before anyone was asked. */
export type LocationPermission = 'unknown' | 'undetermined' | 'granted' | 'denied';

type LocationState = {
  /** The Gigs tab's card was answered ("Use my location" or "Not now"), so it is not shown again. */
  asked: boolean;
  /** Read from the phone at each launch, never kept. */
  permission: LocationPermission;
  /** False once Android stops showing its prompt ("Don't allow" twice): only its Settings can change it. */
  canAskAgain: boolean;
  /** The rounded position, kept in memory only. */
  here: Position | null;
  readAt: number | null;
  /** The first check is over (and, when allowed, the first reading or its time-out): lists may load. */
  ready: boolean;
};

// The same small key-value file as the theme (MMKV on Android, localStorage on the web). Only `asked`
// is kept there; the position never is.
const mmkv = new MMKV({ id: 'soundcheck.prefs' });

export const useDeviceLocation = create<LocationState>()(
  persist(
    (): LocationState => ({
      asked: false,
      permission: 'unknown',
      canAskAgain: true,
      here: null,
      readAt: null,
      ready: false,
    }),
    {
      name: 'location',
      version: 1,
      storage: createJSONStorage(() => ({
        getItem: (key) => mmkv.getString(key) ?? null,
        setItem: (key, value) => mmkv.set(key, value),
        removeItem: (key) => {
          mmkv.delete(key);
        },
      })),
      partialize: (state) => ({ asked: state.asked }),
    },
  ),
);

const set = useDeviceLocation.setState;

function permissionOf(response: Location.LocationPermissionResponse): LocationPermission {
  if (response.granted) return 'granted';
  return response.status === Location.PermissionStatus.UNDETERMINED ? 'undetermined' : 'denied';
}

/** Keeps a new reading only when it lands on another rounded point, so the lists refetch only then. */
function keep(lat: number, lng: number, now: number) {
  const next = approximate(lat, lng);
  const { here } = useDeviceLocation.getState();
  const same = here !== null && here.lat === next.lat && here.lng === next.lng;
  set({ here: same ? here : next, readAt: now, ready: true });
}

/**
 * A reading when the last one is stale: the phone's last known fix if it is recent, else a new
 * low-accuracy one. A slow first fix does not hold the lists back: after the time-out they load with the
 * home city, and the reading, when it comes, makes them refetch.
 */
async function read(now: number, force: boolean): Promise<void> {
  const { here, readAt } = useDeviceLocation.getState();
  if (!force && here !== null && readAt !== null && now - readAt < STALE_MS) {
    set({ ready: true });
    return;
  }
  const last = await Location.getLastKnownPositionAsync({ maxAge: STALE_MS }).catch(() => null);
  if (last) {
    keep(last.coords.latitude, last.coords.longitude, now);
    return;
  }
  const current = Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Low }).then(
    (fix) => keep(fix.coords.latitude, fix.coords.longitude, Date.now()),
    () => undefined,
  );
  let timer: ReturnType<typeof setTimeout> | undefined;
  await Promise.race([
    current,
    new Promise<void>((resolve) => {
      timer = setTimeout(resolve, FIRST_READ_TIMEOUT_MS);
    }),
  ]);
  clearTimeout(timer);
  set({ ready: true });
}

let inFlight: Promise<void> | null = null;

/**
 * Checks what the phone allows and, when location is allowed, reads it if the last reading is stale.
 * Never asks: only the Gigs tab's card does. One check at a time.
 */
export function syncLocation(now: number = Date.now()): Promise<void> {
  inFlight ??= (async () => {
    try {
      const response = await Location.getForegroundPermissionsAsync();
      set({ permission: permissionOf(response), canAskAgain: response.canAskAgain });
      if (response.granted) {
        await read(now, false);
      } else {
        set({ here: null, readAt: null, ready: true });
      }
    } catch {
      // No location on this device or browser: distances stay from the home city.
      set({ ready: true });
    }
  })().finally(() => {
    inFlight = null;
  });
  return inFlight;
}

/** "Use my location": the phone's own prompt, then a first reading; after "Don't allow" for good, Settings. */
export async function askForLocation(): Promise<void> {
  set({ asked: true });
  const { permission, canAskAgain } = useDeviceLocation.getState();
  if (permission === 'denied' && !canAskAgain) {
    await Linking.openSettings().catch(() => undefined);
    return;
  }
  try {
    const response = await Location.requestForegroundPermissionsAsync();
    set({ permission: permissionOf(response), canAskAgain: response.canAskAgain });
    if (response.granted) {
      await read(Date.now(), true);
    }
  } catch {
    set({ ready: true });
  }
}

/** "Not now": the card goes, and distances stay from the home city. */
export function declineLocation(): void {
  set({ asked: true });
}

/**
 * For a screen whose list shows distances: the `fromLat` / `fromLng` to send (none without a position),
 * and whether the first check is over, to pass as the query's `enabled` so the list is not fetched twice
 * on the way in. Checks when the screen mounts and when the app comes back to the foreground (the user
 * may have changed the permission in Settings meanwhile).
 */
export function useDistanceOrigin(): {
  params: { fromLat?: number; fromLng?: number };
  ready: boolean;
  here: Position | null;
} {
  const here = useDeviceLocation((s) => s.here);
  const ready = useDeviceLocation((s) => s.ready);
  useEffect(() => {
    void syncLocation();
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') void syncLocation();
    });
    return () => subscription.remove();
  }, []);
  const params = useMemo(() => distanceParams(here), [here]);
  return { params, ready, here };
}
