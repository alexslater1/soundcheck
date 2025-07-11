import * as Location from 'expo-location';
import { Linking } from 'react-native';

import { askForLocation, declineLocation, syncLocation, useDeviceLocation } from './deviceLocation';
import { FIRST_READ_TIMEOUT_MS, STALE_MS } from './position';

const mocked = jest.mocked(Location);

const permission = (status: 'granted' | 'denied' | 'undetermined', canAskAgain = true) =>
  ({ granted: status === 'granted', status, canAskAgain, expires: 'never' }) as never;
const fix = (latitude: number, longitude: number) =>
  ({ coords: { latitude, longitude }, timestamp: Date.now() }) as never;

beforeEach(() => {
  // Back to "never asked" (as in jest.setup.ts), so no answer leaks from one test into the next.
  mocked.getForegroundPermissionsAsync.mockReset().mockResolvedValue(permission('undetermined'));
  mocked.requestForegroundPermissionsAsync.mockReset().mockResolvedValue(permission('denied'));
  mocked.getLastKnownPositionAsync.mockReset().mockResolvedValue(null);
  mocked.getCurrentPositionAsync.mockReset().mockReturnValue(new Promise(() => undefined));
  useDeviceLocation.setState({
    asked: false,
    permission: 'unknown',
    canAskAgain: true,
    here: null,
    readAt: null,
    ready: false,
  });
});

describe('syncLocation', () => {
  it('never asks: before anyone was asked, the lists go ahead without a position', async () => {
    mocked.getForegroundPermissionsAsync.mockResolvedValueOnce(permission('undetermined'));

    await syncLocation();

    expect(useDeviceLocation.getState()).toMatchObject({
      permission: 'undetermined',
      here: null,
      ready: true,
    });
    expect(mocked.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
    expect(mocked.getLastKnownPositionAsync).not.toHaveBeenCalled();
  });

  it('when allowed, keeps the last known fix rounded to 0.01°', async () => {
    mocked.getForegroundPermissionsAsync.mockResolvedValueOnce(permission('granted'));
    mocked.getLastKnownPositionAsync.mockResolvedValueOnce(fix(51.507351, -0.127758));

    await syncLocation();

    expect(useDeviceLocation.getState()).toMatchObject({
      permission: 'granted',
      here: { lat: 51.51, lng: -0.13 },
      ready: true,
    });
    expect(mocked.getLastKnownPositionAsync).toHaveBeenCalledWith({ maxAge: STALE_MS });
  });

  it('keeps the same point while a new reading rounds to it, so the lists do not refetch', async () => {
    mocked.getForegroundPermissionsAsync.mockResolvedValue(permission('granted'));
    mocked.getLastKnownPositionAsync.mockResolvedValueOnce(fix(51.5071, -0.1281));
    await syncLocation(1_000);
    const first = useDeviceLocation.getState().here;

    mocked.getLastKnownPositionAsync.mockResolvedValueOnce(fix(51.5074, -0.1279));
    await syncLocation(1_000 + STALE_MS + 1);

    expect(useDeviceLocation.getState().here).toBe(first);
  });

  it('does not read again while the last reading is fresh, and checks one at a time', async () => {
    mocked.getForegroundPermissionsAsync.mockResolvedValue(permission('granted'));
    mocked.getLastKnownPositionAsync.mockResolvedValue(fix(53.48, -2.24));

    await Promise.all([syncLocation(1_000), syncLocation(1_000)]);
    await syncLocation(2_000);

    expect(mocked.getForegroundPermissionsAsync).toHaveBeenCalledTimes(2);
    expect(mocked.getLastKnownPositionAsync).toHaveBeenCalledTimes(1);
  });

  it('lets the lists load after the time-out when there is no recent fix, then uses the late one', async () => {
    jest.useFakeTimers();
    try {
      let resolve: (value: never) => void = () => undefined;
      mocked.getForegroundPermissionsAsync.mockResolvedValueOnce(permission('granted'));
      mocked.getCurrentPositionAsync.mockReturnValueOnce(new Promise((r) => (resolve = r)));

      const done = syncLocation();
      await jest.advanceTimersByTimeAsync(FIRST_READ_TIMEOUT_MS);
      await done;
      expect(useDeviceLocation.getState()).toMatchObject({ here: null, ready: true });

      resolve(fix(52.4862, -1.8904));
      await jest.advanceTimersByTimeAsync(0);
      expect(useDeviceLocation.getState().here).toEqual({ lat: 52.49, lng: -1.89 });
    } finally {
      jest.useRealTimers();
    }
  });

  it('forgets the position when location is switched off in Settings', async () => {
    useDeviceLocation.setState({ here: { lat: 51.51, lng: -0.13 }, readAt: 1 });
    mocked.getForegroundPermissionsAsync.mockResolvedValueOnce(permission('denied'));

    await syncLocation();

    expect(useDeviceLocation.getState()).toMatchObject({ permission: 'denied', here: null });
  });

  it('carries on without a position when the phone or browser has no location at all', async () => {
    mocked.getForegroundPermissionsAsync.mockRejectedValueOnce(new Error('unavailable'));

    await syncLocation();

    expect(useDeviceLocation.getState()).toMatchObject({ here: null, ready: true });
  });
});

describe('askForLocation and declineLocation', () => {
  it('"Use my location" shows the phone’s prompt, then reads the position', async () => {
    mocked.requestForegroundPermissionsAsync.mockResolvedValueOnce(permission('granted'));
    mocked.getLastKnownPositionAsync.mockResolvedValueOnce(fix(51.4545, -2.5879));

    await askForLocation();

    expect(useDeviceLocation.getState()).toMatchObject({
      asked: true,
      permission: 'granted',
      here: { lat: 51.45, lng: -2.59 },
    });
  });

  it('after "Don’t allow" for good, opens the app’s settings instead of a prompt that never shows', async () => {
    const openSettings = jest.spyOn(Linking, 'openSettings').mockResolvedValueOnce(undefined);
    useDeviceLocation.setState({ permission: 'denied', canAskAgain: false });

    await askForLocation();

    expect(openSettings).toHaveBeenCalled();
    expect(mocked.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
  });

  it('"Not now" only records that the card was answered', () => {
    declineLocation();
    expect(useDeviceLocation.getState()).toMatchObject({ asked: true, here: null });
  });

  it('keeps only the answer on the device, never the position', () => {
    useDeviceLocation.setState({ asked: true, here: { lat: 51.51, lng: -0.13 }, readAt: 5 });
    const stored = useDeviceLocation.persist
      .getOptions()
      .partialize?.(useDeviceLocation.getState());
    expect(stored).toEqual({ asked: true });
  });
});
