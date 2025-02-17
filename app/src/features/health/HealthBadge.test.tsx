import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { HealthBadge } from './HealthBadge';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('HealthBadge', () => {
  it('shows a loading state, then the API status', async () => {
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({ status: 'UP', version: '0.1.0', time: '2026-10-01T20:00:00Z' }),
        {
          status: 200,
          headers: { 'content-type': 'application/json' },
        },
      ),
    );

    await render(<HealthBadge />, { wrapper: Providers });

    expect(screen.getByText('Checking API…')).toBeOnTheScreen();
    await waitFor(() => expect(screen.getByTestId('health-status')).toHaveTextContent('API: UP'));
    expect(fetchMock.mock.calls[0]![0]).toBe('http://localhost:8088/api/v1/health');
  });

  it('shows an error state when the API is down', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await render(<HealthBadge />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByText('API: unreachable')).toBeOnTheScreen());
  });
});
