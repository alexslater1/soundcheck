import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { ApiError } from '@/api/http';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PastGigSearch } from './PastGigSearch';

const mockSearch = jest.fn();
const mockAddGig = jest.fn();
jest.mock('@/api/generated/past-gigs/past-gigs', () => ({
  useSearchPastGigs: (...args: unknown[]) => mockSearch(...args),
}));
jest.mock('@/api/generated/catalog/catalog', () => ({
  addGig: (...args: unknown[]) => mockAddGig(...args),
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const found = {
  setlistId: 's1',
  url: 'https://www.setlist.fm/setlist/a/2025/b-s1.html',
  date: '2025-10-17',
  artistName: 'Pale Kites',
  venueName: 'The Snug',
  cityName: 'Manchester',
  lat: 53.48,
  lng: -2.24,
};

async function renderSearch() {
  const onAdded = jest.fn();
  await render(
    <ThemeProvider>
      <PastGigSearch initialArtist="Pale Kites" onAdded={onAdded} />
    </ThemeProvider>,
  );
  return onAdded;
}

describe('PastGigSearch', () => {
  beforeEach(() => jest.clearAllMocks());

  it("lists the artist's past UK gigs; tapping one adds it with the link back and selects it", async () => {
    mockSearch.mockReturnValue({ data: { items: [found] }, isFetching: false, isError: false });
    mockAddGig.mockResolvedValue({ item: { id: 'g1' }, created: true });
    const onAdded = await renderSearch();

    await waitFor(() =>
      expect(mockSearch).toHaveBeenLastCalledWith(
        { artist: 'Pale Kites' },
        expect.objectContaining({ query: expect.objectContaining({ enabled: true }) }),
      ),
    );
    await fireEvent.press(screen.getByLabelText('It was Pale Kites at The Snug on 2025-10-17'));

    await waitFor(() => expect(onAdded).toHaveBeenCalledWith({ id: 'g1' }));
    expect(mockAddGig.mock.calls[0]![0]).toMatchObject({
      foundOn: { source: 'SETLISTFM', externalId: 's1' },
      newVenue: { name: 'The Snug', cityName: 'Manchester' },
    });
    expect(screen.getByText('Concert history from setlist.fm')).toBeTruthy();
  });

  it('narrows to a year', async () => {
    mockSearch.mockReturnValue({ data: { items: [] }, isFetching: false, isError: false });
    await renderSearch();
    await fireEvent.press(screen.getByText('2025'));
    await waitFor(() =>
      expect(mockSearch).toHaveBeenLastCalledWith(
        { artist: 'Pale Kites', year: 2025 },
        expect.anything(),
      ),
    );
  });

  it('is hidden when the server has no setlist.fm key', async () => {
    mockSearch.mockReturnValue({
      data: undefined,
      isFetching: false,
      isError: true,
      error: new ApiError(503, 'PAST_GIGS_UNAVAILABLE', 'not set up'),
    });
    await renderSearch();
    expect(screen.queryByTestId('past-gig-search')).toBeNull();
  });
});
