import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { ApiError } from '@/api/http';
import ArtistsStep from '@/app/onboarding/artists';
import { useAuthStore } from '@/features/auth/store';

import { saveErrorMessage } from './useSaveStep';

// The step saves through the generated useCompleteOnboarding mutation hook, so the test drives the
// real hook and http.ts and fakes only the network.
const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();
const reloadMe = jest.fn<Promise<void>, []>();

function Providers({ children }: PropsWithChildren) {
  // gcTime Infinity: no garbage-collection timer is left running when the test ends.
  const client = new QueryClient({ defaultOptions: { mutations: { gcTime: Infinity } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  reloadMe.mockReset().mockResolvedValue(undefined);
  useAuthStore.setState({ reloadMe });
});

describe('saveErrorMessage', () => {
  it('uses the Problem Details message, a network hint, or a generic fallback', () => {
    expect(saveErrorMessage(new ApiError(400, 'CITY_UNKNOWN', 'Unknown city id 7'))).toBe(
      'Unknown city id 7',
    );
    expect(saveErrorMessage(new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server'))).toBe(
      'Could not reach the server. Check your connection and try again.',
    );
    expect(saveErrorMessage(new Error('boom'))).toBe('Something went wrong. Please try again.');
  });
});

describe('onboarding step error state (artists step)', () => {
  it('shows the error, keeps the user on the step, and lets them retry', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await render(<ArtistsStep />, { wrapper: Providers });

    await fireEvent.press(screen.getByRole('button', { name: 'Finish' }));

    await waitFor(() =>
      expect(screen.getByTestId('step-error')).toHaveTextContent(
        'Could not reach the server. Check your connection and try again.',
      ),
    );
    expect(reloadMe).not.toHaveBeenCalled();

    // Retry succeeds: the error clears and /me is reloaded (the root layout then redirects).
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ onboarded: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Finish' }));
    await waitFor(() => expect(reloadMe).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('step-error')).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1]![0]).toBe('http://localhost:8088/api/v1/me/onboarding/complete');
    expect(fetchMock.mock.calls[1]![1]?.method).toBe('POST');
  });
});
