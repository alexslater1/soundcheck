import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { FollowButton } from './FollowButton';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({ defaultOptions: { mutations: { gcTime: Infinity } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('FollowButton', () => {
  it('follows optimistically with a PUT', async () => {
    let answer: (response: Response) => void = () => {};
    fetchMock.mockReturnValueOnce(new Promise<Response>((resolve) => (answer = resolve)));
    await render(<FollowButton artistId="a1" artistName="Pale Kites" isFollowing={false} />, {
      wrapper: Providers,
    });

    await fireEvent.press(screen.getByRole('button', { name: 'Follow Pale Kites' }));

    await waitFor(() => expect(screen.getByText('Following')).toBeOnTheScreen());
    expect(fetchMock.mock.calls[0]![0]).toBe('http://localhost:8088/api/v1/artists/a1/follow');
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('PUT');
    answer(new Response(null, { status: 204 }));
  });

  it('goes back to the server state and offers a retry when the request fails', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await render(<FollowButton artistId="a1" artistName="Pale Kites" isFollowing />, {
      wrapper: Providers,
    });

    await fireEvent.press(screen.getByRole('button', { name: 'Unfollow Pale Kites' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('DELETE');
    // Back to the server's state ("following"), and the button says the change was not saved.
    await waitFor(() => expect(screen.getByText('Try again')).toBeOnTheScreen());
    expect(
      screen.getByRole('button', { name: 'Could not save. Try again: unfollow Pale Kites' }),
    ).toBeOnTheScreen();
  });
});
