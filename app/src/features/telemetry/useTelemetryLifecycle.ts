import { useEffect } from 'react';
import { AppState, Platform } from 'react-native';

import { telemetry } from './telemetry';

/**
 * Sends what is waiting when the app is about to go away: the app going to the
 * background on Android, the tab being hidden on web. Mounted once, at the root.
 */
export function useTelemetryLifecycle(): void {
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active') {
        void telemetry.flush();
      }
    });
    let removeWeb = () => {};
    if (Platform.OS === 'web' && typeof document !== 'undefined') {
      const onVisibility = () => {
        if (document.visibilityState === 'hidden') {
          void telemetry.flush();
        }
      };
      document.addEventListener('visibilitychange', onVisibility);
      removeWeb = () => document.removeEventListener('visibilitychange', onVisibility);
    }
    return () => {
      subscription.remove();
      removeWeb();
    };
  }, []);
}
