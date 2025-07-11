import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { LocationScreen } from './LocationScreen';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();
const reloadMe = jest.fn<Promise<void>, []>();
const mockBack = jest.fn();
let client: QueryClient;

jest.mock('expo-router', () => ({ useRouter: () => ({ back: mockBack }) }));

function Providers({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

describe('Settings → Your city', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    client = new QueryClient({
      defaultOptions: {
        queries: { gcTime: Infinity, retry: false },
        mutations: { gcTime: Infinity },
      },
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    reloadMe.mockResolvedValue(undefined);
    useAuthStore.setState({
      me: { homeCity: { id: 1, name: 'London' }, genres: [] } as never,
      reloadMe,
    });
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.includes('/api/v1/cities')) {
        return json([
          { id: 1, name: 'London', region: 'England' },
          { id: 7, name: 'Manchester', region: 'England' },
        ]);
      }
      if (url.endsWith('/api/v1/me') && init?.method === 'PATCH') return json({});
      throw new Error(`unexpected request ${url}`);
    });
  });

  it('starts from the current city, saves a new one, refreshes what depends on it and goes back', async () => {
    client.setQueryData(['/api/v1/gigs', { limit: 20 }], { items: [] });
    await render(<LocationScreen />, { wrapper: Providers });
    expect(screen.getByRole('button', { name: 'Save London' })).toBeTruthy();
    await waitFor(() => expect(screen.getByText('Manchester')).toBeTruthy());

    await fireEvent.press(screen.getByText('Manchester'));
    await fireEvent.press(screen.getByRole('button', { name: 'Save Manchester' }));

    await waitFor(() => expect(mockBack).toHaveBeenCalledTimes(1));
    const patch = fetchMock.mock.calls.find(([, init]) => init?.method === 'PATCH');
    expect(JSON.parse(String(patch?.[1]?.body))).toEqual({ homeCityId: 7 });
    expect(reloadMe).toHaveBeenCalledTimes(1);
    expect(client.getQueryState(['/api/v1/gigs', { limit: 20 }])?.isInvalidated).toBe(true);
  });
});
