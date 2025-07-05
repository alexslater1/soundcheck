import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { EMPTY_SEARCH } from './gigFilters';
import { SavedFilters } from './SavedFilters';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

const filter = (alerts: boolean) => ({
  id: 'f1',
  name: 'Keo in London',
  artists: [{ id: 'a1', name: 'Keo' }],
  venues: [],
  genre: null,
  q: null,
  place: { kind: 'UK', cityId: null, cityName: null, lat: null, lng: null },
  alerts,
});

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('SavedFilters alerts', () => {
  it('marks a saved search that alerts, and switches its alerts off from Edit', async () => {
    fetchMock.mockImplementation((url, init) =>
      Promise.resolve(
        init?.method === 'PATCH' ? json(200, filter(false)) : json(200, [filter(true)]),
      ),
    );
    await render(<SavedFilters search={EMPTY_SEARCH} onApply={jest.fn()} />, {
      wrapper: Providers,
    });

    await waitFor(() => expect(screen.getByLabelText('New-gig alerts on')).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Edit saved searches' }));
    await fireEvent.press(
      screen.getByRole('switch', { name: 'Turn off new-gig alerts for Keo in London' }),
    );

    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PATCH')).toBe(true),
    );
    const [url, init] = fetchMock.mock.calls.find(([, i]) => i?.method === 'PATCH')!;
    expect(url).toBe('http://localhost:8088/api/v1/me/gig-filters/f1');
    expect(JSON.parse(String(init?.body))).toEqual({ alerts: false });
  });
});
