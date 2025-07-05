import { useEffect } from 'react';
import { AppState } from 'react-native';

import { Screen } from '@/components/ui/Screen';
import { StateMessage } from '@/components/ui/StateMessage';

/**
 * Shown at start when the session could not be resumed because the server could not be reached.
 * Nothing was refused, so this is not the sign-in screen: the refresh token is kept, and trying again
 * (the button, or coming back to the app) signs straight in.
 */
export function UnreachableScreen({ onRetry }: { onRetry: () => Promise<void> | void }) {
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') void onRetry();
    });
    return () => subscription.remove();
  }, [onRetry]);

  return (
    <Screen>
      <StateMessage
        title="Can't connect to Soundcheck"
        detail="You're still signed in. Check your connection, then try again."
        onRetry={() => void onRetry()}
        testID="unreachable"
      />
    </Screen>
  );
}
