import { renderHook } from '@testing-library/react-native';
import * as SplashScreen from 'expo-splash-screen';

import { holdSplash, SPLASH_MAX_MS, useSplashGate } from './splash';

jest.mock('expo-splash-screen', () => ({
  preventAutoHideAsync: jest.fn(async () => true),
  hide: jest.fn(),
}));

const hide = SplashScreen.hide as jest.Mock;
const prevent = SplashScreen.preventAutoHideAsync as jest.Mock;

describe('splash screen', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    hide.mockReset();
    prevent.mockClear();
  });
  afterEach(() => jest.useRealTimers());

  it('keeps the splash up from the start, and a refusal from the native side is not an error', async () => {
    holdSplash();
    expect(prevent).toHaveBeenCalledTimes(1);
    prevent.mockRejectedValueOnce(new Error('no splash'));
    expect(() => holdSplash()).not.toThrow();
  });

  it('hides once the app is ready, and only once', async () => {
    const hook = await renderHook((props: { ready: boolean }) => useSplashGate(props.ready), {
      initialProps: { ready: false },
    });
    expect(hide).not.toHaveBeenCalled();

    await hook.rerender({ ready: true });
    expect(hide).toHaveBeenCalledTimes(1);

    await hook.rerender({ ready: true });
    jest.advanceTimersByTime(SPLASH_MAX_MS * 2);
    expect(hide).toHaveBeenCalledTimes(1);
  });

  it('never stays up forever: it hides after the time limit even if the app never gets ready', async () => {
    const hook = await renderHook((props: { ready: boolean }) => useSplashGate(props.ready), {
      initialProps: { ready: false },
    });
    jest.advanceTimersByTime(SPLASH_MAX_MS - 1);
    expect(hide).not.toHaveBeenCalled();
    jest.advanceTimersByTime(1);
    expect(hide).toHaveBeenCalledTimes(1);

    await hook.rerender({ ready: true });
    expect(hide).toHaveBeenCalledTimes(1);
  });

  it('a splash that is already gone does not break the app', async () => {
    hide.mockImplementation(() => {
      throw new Error('already hidden');
    });
    // RNTL 13's renderHook is synchronous: a throw would come out of the call itself.
    expect(renderHook(() => useSplashGate(true))).toBeDefined();
    expect(hide).toHaveBeenCalledTimes(1);
  });
});
