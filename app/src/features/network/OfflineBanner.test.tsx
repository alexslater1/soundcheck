import { act, render, screen } from '@testing-library/react-native';
import type { NetworkState } from 'expo-network';

import { queryClient } from '@/api/queryClient';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { isOffline, OfflineBanner } from './OfflineBanner';

let mockState: NetworkState = {};
jest.mock('expo-network', () => ({ useNetworkState: () => mockState }));
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 24, bottom: 0, left: 0, right: 0 }),
}));

const view = () => (
  <ThemeProvider>
    <OfflineBanner />
  </ThemeProvider>
);

describe('OfflineBanner', () => {
  it('counts as offline only when the phone says so', () => {
    expect(isOffline({})).toBe(false);
    expect(isOffline({ isConnected: true, isInternetReachable: true })).toBe(false);
    expect(isOffline({ isConnected: false })).toBe(true);
    expect(isOffline({ isConnected: true, isInternetReachable: false })).toBe(true);
  });

  it('shows while offline and fetches what is on screen again when the connection is back', async () => {
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries').mockResolvedValue();
    mockState = { isConnected: true, isInternetReachable: true };
    const rendered = await render(view());
    expect(screen.queryByTestId('offline-banner')).toBeNull();

    mockState = { isConnected: false, isInternetReachable: false };
    await act(async () => rendered.rerender(view()));
    expect(screen.getByText(/No connection/)).toBeOnTheScreen();
    expect(invalidate).not.toHaveBeenCalled();

    mockState = { isConnected: true, isInternetReachable: true };
    await act(async () => rendered.rerender(view()));
    expect(screen.queryByTestId('offline-banner')).toBeNull();
    expect(invalidate).toHaveBeenCalledWith({ refetchType: 'active' });
    invalidate.mockRestore();
  });
});
