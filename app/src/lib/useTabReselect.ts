import { useNavigation } from 'expo-router';
import { useEffect, useRef } from 'react';

/** The part of the tab navigator's navigation object this hook needs. */
type TabNavigation = {
  isFocused: () => boolean;
  addListener: (event: 'tabPress', listener: () => void) => () => void;
};

/**
 * Calls `onReselect` when the user presses the tab of the screen that is already showing (the usual
 * "tap Home again to go back to the top and refresh"). A press that switches to the tab is not a
 * reselect: the screen is not focused yet when the event fires.
 */
export function useTabReselect(onReselect: () => void): void {
  const navigation = useNavigation() as unknown as TabNavigation;
  const latest = useRef(onReselect);
  useEffect(() => {
    latest.current = onReselect;
  });
  useEffect(
    () =>
      navigation.addListener('tabPress', () => {
        if (navigation.isFocused()) {
          latest.current();
        }
      }),
    [navigation],
  );
}
