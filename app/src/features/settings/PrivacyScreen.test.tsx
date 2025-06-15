import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PrivacyScreen } from './PrivacyScreen';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();
const reloadMe = jest.fn(async () => undefined);

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const me = {
  id: 'user-1',
  handle: 'anna',
  displayName: 'Anna',
  genres: [],
  counts: { posts: 0, followers: 0, following: 0 },
  showGigStats: false,
};

// The line under the choice says which one is in force.
const detail = () => screen.getByTestId('privacy-stats-detail');

describe('PrivacyScreen', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    reloadMe.mockClear();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    useAuthStore.setState({ me: me as never, reloadMe });
  });

  it('starts private and shares the stats when Everyone is picked', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ ...me, showGigStats: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
    // Reloading the account brings the saved choice into the store, as the real store does.
    reloadMe.mockImplementationOnce(async () => {
      useAuthStore.setState({ me: { ...me, showGigStats: true } as never });
    });
    await render(<PrivacyScreen />, { wrapper: Providers });
    expect(detail()).toHaveTextContent(/for you only/);

    await fireEvent.press(screen.getByText('Everyone'));

    await waitFor(() => expect(reloadMe).toHaveBeenCalled());
    expect(detail()).toHaveTextContent(/to anyone signed in/);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(String(url)).toContain('/api/v1/me');
    expect(init?.method).toBe('PATCH');
    expect(JSON.parse(String(init?.body))).toEqual({ showGigStats: true });
  });

  it('shows the privacy notice: city only, clips without metadata, no trackers, the 7-day purge', async () => {
    await render(<PrivacyScreen />, { wrapper: Providers });
    const notice = screen.getByTestId('privacy-notice');
    expect(notice).toHaveTextContent(/never your phone’s location/);
    expect(notice).toHaveTextContent(/without the file’s metadata/);
    expect(notice).toHaveTextContent(/no advertising or third-party tracking tools/);
    expect(notice).toHaveTextContent(/Seven days later/);
  });

  it('puts the choice back and says so when saving fails', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ status: 503, code: 'UNAVAILABLE' }), {
        status: 503,
        headers: { 'content-type': 'application/problem+json' },
      }),
    );
    await render(<PrivacyScreen />, { wrapper: Providers });

    await fireEvent.press(screen.getByText('Everyone'));

    await waitFor(() => expect(screen.getByTestId('privacy-error')).toBeOnTheScreen());
    expect(detail()).toHaveTextContent(/for you only/);
    expect(reloadMe).not.toHaveBeenCalled();
  });
});
