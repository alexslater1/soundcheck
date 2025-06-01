import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { ukDate } from '@/features/calendar/monthGrid';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { AddGigForm } from './AddGigForm';

const mockAddGig = jest.fn();
jest.mock('@/api/generated/catalog/catalog', () => ({
  addGig: (...args: unknown[]) => mockAddGig(...args),
  useListArtists: ({ q }: { q: string }) => ({
    data: {
      items: q === 'pale' ? [{ id: 'a1', name: 'Pale Kites', genres: [], isFollowing: false }] : [],
    },
  }),
  useSearchVenues: ({ q }: { q: string }) => ({
    data: q === 'apollo' ? [{ id: 'v1', name: 'Apollo', city: 'Manchester', location: null }] : [],
  }),
}));
jest.mock('@/api/generated/reference/reference', () => ({
  useSearchCities: ({ q }: { q: string }) => ({
    data: q === 'man' ? [{ id: 5, name: 'Manchester', region: 'England', lat: 0, lng: 0 }] : [],
  }),
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
// The setlist.fm search has its own test.
jest.mock('./PastGigSearch', () => ({ PastGigSearch: () => null }));

async function renderForm() {
  const onAdded = jest.fn();
  await render(
    <ThemeProvider>
      <AddGigForm onAdded={onAdded} onCancel={jest.fn()} />
    </ThemeProvider>,
  );
  return onAdded;
}

describe('AddGigForm', () => {
  beforeEach(() => jest.clearAllMocks());

  it('adds a gig with a known act, a new act and a new venue in a city, last night at 21:30', async () => {
    mockAddGig.mockResolvedValue({ item: { id: 'g-new' }, created: true });
    const onAdded = await renderForm();
    expect(screen.getByTestId('add-gig-submit')).toBeDisabled();

    await fireEvent.changeText(screen.getByLabelText('Act name'), 'pale');
    await fireEvent.press(await screen.findByText('Pale Kites'));
    await fireEvent.changeText(screen.getByLabelText('Act name'), 'Night  Bus');
    await fireEvent.press(await screen.findByText('+ New act “Night  Bus”'));

    await fireEvent.changeText(screen.getByLabelText('Venue name'), 'The Snug');
    await fireEvent.press(await screen.findByText('+ New venue “The Snug”'));
    await fireEvent.changeText(screen.getByLabelText('Venue city'), 'man');
    await fireEvent.press(await screen.findByText('Manchester, England'));

    await fireEvent.press(screen.getByText('Yesterday'));
    await fireEvent.changeText(screen.getByLabelText('Start time'), '21:30');
    await fireEvent.press(screen.getByTestId('add-gig-submit'));

    const yesterday = ukDate(new Date(Date.now() - 24 * 60 * 60 * 1000));
    await waitFor(() => expect(onAdded).toHaveBeenCalledWith({ id: 'g-new' }));
    expect(mockAddGig).toHaveBeenCalledWith({
      artistIds: ['a1'],
      artistNames: ['Night Bus'],
      newVenue: { name: 'The Snug', cityId: 5 },
      date: yesterday,
      time: '21:30',
    });
  });

  it('uses a known venue, and refuses a time it cannot read', async () => {
    mockAddGig.mockResolvedValue({ item: { id: 'g2' }, created: false });
    await renderForm();
    await fireEvent.changeText(screen.getByLabelText('Act name'), 'pale');
    await fireEvent.press(await screen.findByText('Pale Kites'));
    await fireEvent.changeText(screen.getByLabelText('Venue name'), 'apollo');
    await fireEvent.press(await screen.findByText('Apollo, Manchester'));

    await fireEvent.changeText(screen.getByLabelText('Start time'), '8pm');
    expect(screen.getByText('Write the time as 20:00.')).toBeTruthy();
    expect(screen.getByTestId('add-gig-submit')).toBeDisabled();

    await fireEvent.changeText(screen.getByLabelText('Start time'), '');
    await fireEvent.press(screen.getByTestId('add-gig-submit'));
    await waitFor(() => expect(mockAddGig).toHaveBeenCalledTimes(1));
    expect(mockAddGig.mock.calls[0]![0]).toMatchObject({
      venueId: 'v1',
      artistIds: ['a1'],
      artistNames: [],
    });
    expect(mockAddGig.mock.calls[0]![0]).not.toHaveProperty('time');
  });
});
