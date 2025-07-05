import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { VenueFollowButton } from './VenueFollowButton';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({ defaultOptions: { mutations: { gcTime: Infinity } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('VenueFollowButton', () => {
  it('follows a venue optimistically with a PUT', async () => {
    let answer: (response: Response) => void = () => {};
    fetchMock.mockReturnValueOnce(new Promise<Response>((resolve) => (answer = resolve)));
    await render(<VenueFollowButton venueId="v1" venueName="MOTH Club" isFollowing={false} />, {
      wrapper: Providers,
    });

    await fireEvent.press(
      screen.getByRole('button', { name: 'Follow MOTH Club: hear about its new gigs' }),
    );

    await waitFor(() => expect(screen.getByText('Following')).toBeOnTheScreen());
    expect(fetchMock.mock.calls[0]![0]).toBe('http://localhost:8088/api/v1/venues/v1/follow');
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('PUT');
    answer(new Response(null, { status: 204 }));
  });

  it('unfollows with a DELETE, and offers a retry when that fails', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await render(<VenueFollowButton venueId="v1" venueName="MOTH Club" isFollowing />, {
      wrapper: Providers,
    });

    await fireEvent.press(screen.getByRole('button', { name: 'Unfollow MOTH Club' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('DELETE');
    await waitFor(() => expect(screen.getByText('Try again')).toBeOnTheScreen());
  });
});
