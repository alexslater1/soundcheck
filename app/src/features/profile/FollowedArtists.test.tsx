import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { FollowedArtists } from './FollowedArtists';

jest.mock('expo-router', () => ({ Link: ({ children }: { children: ReactNode }) => children }));
jest.mock('expo-image', () => ({ Image: () => null }));
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

const json = (body: unknown) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });

const artist = (id: string, name: string, isFollowing: boolean) => ({
  id,
  name,
  genres: [],
  imageUrl: null,
  isFollowing,
});

function respond(own: boolean) {
  fetchMock.mockImplementation(async (input) => {
    const url = String(input);
    if (url.includes('/following/artists')) {
      return json({
        items: [
          { artist: artist('a1', 'KEO', true), notifyLevel: own ? 'ALL' : null },
          { artist: artist('a2', 'Tooth', false), notifyLevel: own ? 'GIGS' : null },
        ],
        nextCursor: 'more',
      });
    }
    if (url.includes('/following/venues')) {
      return json({
        items: [
          {
            venue: { id: 'v1', name: 'Band on the Wall', city: 'Manchester' },
            isFollowing: own,
            notifyLevel: own ? 'POSTS' : null,
          },
        ],
        nextCursor: null,
      });
    }
    return json([]);
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('FollowedArtists: the Artists tab', () => {
  it("lists one's own artists and venues, each with a follow button and a bell", async () => {
    respond(true);
    await render(<FollowedArtists userId="me" own />, { wrapper: Providers });
    expect(await screen.findByText('KEO')).toBeTruthy();
    expect(await screen.findByText('Band on the Wall')).toBeTruthy();
    expect(screen.getByText('Manchester')).toBeTruthy();
    expect(screen.getByLabelText('Unfollow KEO')).toBeTruthy();
    expect(screen.getByLabelText('Alerts for KEO: Everything. Change')).toBeTruthy();
    expect(screen.getByLabelText('Alerts for Band on the Wall: Posts. Change')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Show more artists' })).toBeTruthy();
    expect(String(fetchMock.mock.calls[0]![0])).toContain('/api/v1/users/me/following/artists');
  });

  it("shows someone else's list with the viewer's own follow state and no bells", async () => {
    respond(false);
    await render(<FollowedArtists userId="u2" own={false} />, { wrapper: Providers });
    expect(await screen.findByText('Tooth')).toBeTruthy();
    expect(screen.getByLabelText('Follow Tooth')).toBeTruthy();
    expect(
      await screen.findByLabelText('Follow Band on the Wall: hear about its new gigs'),
    ).toBeTruthy();
    expect(screen.queryByLabelText(/^Alerts for/)).toBeNull();
  });

  it('says so when nothing is followed', async () => {
    fetchMock.mockImplementation(async (input) =>
      json(String(input).includes('/following/') ? { items: [], nextCursor: null } : []),
    );
    await render(<FollowedArtists userId="me" own />, { wrapper: Providers });
    expect(await screen.findByTestId('followed-artists-empty')).toHaveTextContent(
      /You follow no artists yet/,
    );
    expect(await screen.findByTestId('followed-venues-empty')).toBeTruthy();
  });
});
