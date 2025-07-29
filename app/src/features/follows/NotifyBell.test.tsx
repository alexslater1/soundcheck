import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { bellIcon, NotifyBell } from './NotifyBell';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('NotifyBell', () => {
  it('shows the level on the bell', () => {
    expect(bellIcon('ALL')).toBe('notifications');
    expect(bellIcon('GIGS')).toBe('notifications-outline');
    expect(bellIcon('NONE')).toBe('notifications-off-outline');
  });

  it('opens the four levels and saves the one picked', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await render(<NotifyBell kind="artist" id="a1" name="KEO" level="GIGS" />, {
      wrapper: Providers,
    });

    await fireEvent.press(screen.getByLabelText('Alerts for KEO: New gigs. Change'));
    expect(screen.getByRole('radio', { name: /New gigs: New gigs only/ })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: /Everything/ }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(String(url)).toContain('/api/v1/artists/a1/follow/notify');
    expect(init?.method).toBe('PUT');
    expect(JSON.parse(String(init?.body))).toEqual({ level: 'ALL' });
  });

  it('uses the venue endpoint for a venue and says when it was not saved', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ status: 409, code: 'NOT_FOLLOWING' }), {
        status: 409,
        headers: { 'content-type': 'application/problem+json' },
      }),
    );
    await render(<NotifyBell kind="venue" id="v1" name="Moth" level="GIGS" />, {
      wrapper: Providers,
    });
    await fireEvent.press(screen.getByLabelText('Alerts for Moth: New gigs. Change'));
    await fireEvent.press(screen.getByRole('radio', { name: /None/ }));
    expect(await screen.findByText('Not saved')).toBeTruthy();
    expect(String(fetchMock.mock.calls[0]![0])).toContain('/api/v1/venues/v1/follow/notify');
    // Back to the server's level.
    expect(screen.getByLabelText('Alerts for Moth: New gigs. Change')).toBeTruthy();
  });
});
