import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import ArtistsStep from '@/app/onboarding/artists';
import { useAuthStore } from '@/features/auth/store';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({
    defaultOptions: { queries: { gcTime: Infinity, retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

const json = (body: unknown) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });

describe('artists step', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes('/api/v1/artists')) return json({ items: [], nextCursor: null });
      if (url.endsWith('/api/v1/genres')) return json([]);
      throw new Error(`unexpected request ${url}`);
    });
  });

  it('suggests artists of the genres picked in the step before', async () => {
    useAuthStore.setState({
      me: {
        genres: [
          { genreId: 2, weight: 1 },
          { genreId: 14, weight: 1 },
        ],
      } as never,
    });

    await render(<ArtistsStep />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByTestId('artists-empty')).toBeTruthy());
    const urls = fetchMock.mock.calls.map(([input]) => String(input));
    expect(urls.find((u) => u.includes('/api/v1/artists'))).toContain(
      'popular=true&limit=20&genreIds=2&genreIds=14',
    );
    expect(screen.getByText(/Picked for the genres you chose/)).toBeTruthy();
  });
});
