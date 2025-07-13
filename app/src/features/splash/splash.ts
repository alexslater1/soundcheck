import * as SplashScreen from 'expo-splash-screen';
import { useEffect, useRef } from 'react';

/**
 * The native splash screen: the mic-check mark from `ops/scripts/make_icons.py` on the Electric
 * background, configured in app.config.ts. It stays up while the app starts (the session check and
 * the fonts) instead of showing a spinner, and is hidden exactly once. On the web the module is a
 * no-op, so the root layout's own loading view is what the browser shows.
 */

/** The longest the splash may stay up (ms) if the app never reports ready: a hung bootstrap or font load
 * then shows the root layout's own loading view rather than a frozen splash. */
export const SPLASH_MAX_MS = 8000;

/** Keeps the native splash up past the first frame. Call once at module scope, before the first render. */
export function holdSplash(): void {
  SplashScreen.preventAutoHideAsync().catch(() => undefined);
}

/** Hides the splash once: as soon as `ready` is true, or after SPLASH_MAX_MS, whichever comes first. */
export function useSplashGate(ready: boolean): void {
  const hidden = useRef(false);
  useEffect(() => {
    const hide = () => {
      if (hidden.current) return;
      hidden.current = true;
      try {
        SplashScreen.hide();
      } catch {
        // No native splash to hide (already gone): nothing else to do.
      }
    };
    if (ready) {
      hide();
      return undefined;
    }
    const timer = setTimeout(hide, SPLASH_MAX_MS);
    return () => clearTimeout(timer);
  }, [ready]);
}
