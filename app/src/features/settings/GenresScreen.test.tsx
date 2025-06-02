import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { GenresScreen } from './GenresScreen';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();
const reloadMe = jest.fn<Promise<void>, []>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { gcTime: Infinity, retry: false },
      mutations: { gcTime: Infinity },
    },
  });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

describe('Settings → Your genres', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    reloadMe.mockReset().mockResolvedValue(undefined);
    useAuthStore.setState({ me: { genres: [{ genreId: 2, weight: 1 }] } as never, reloadMe });
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith('/api/v1/genres')) {
        return json([{ id: 2, slug: 'indie', name: 'Indie', parentId: 1, aliases: [] }]);
      }
      if (url.endsWith('/api/v1/me/genres') && init?.method === 'PUT') return json({});
      throw new Error(`unexpected request ${url}`);
    });
  });

  it('shows the saved picks with love / like and saves a change of weight', async () => {
    await render(<GenresScreen />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByLabelText('Indie: like it')).toBeTruthy());

    await fireEvent.press(screen.getByLabelText('Indie: like it'));
    await fireEvent.press(screen.getByRole('button', { name: 'Save (1/8)' }));

    await waitFor(() => expect(screen.getByTestId('genres-saved')).toBeTruthy());
    const put = fetchMock.mock.calls.find(([input]) => String(input).endsWith('/api/v1/me/genres'));
    expect(JSON.parse(String(put?.[1]?.body))).toEqual({ genres: [{ genreId: 2, weight: 0.5 }] });
    expect(reloadMe).toHaveBeenCalledTimes(1);
  });
});
