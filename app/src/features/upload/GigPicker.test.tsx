import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useState } from 'react';

import type { GigSummary } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { GigPicker } from './GigPicker';

const gig = (id: string, title: string, support: string[] = []): GigSummary =>
  ({
    id,
    title,
    startsAt: '2026-10-01T19:30:00Z',
    status: 'SCHEDULED',
    venue: { id: 'v1', name: 'The Venue', city: 'Manchester' },
    headliner: { id: `${id}-h`, name: `${title} headliner`, genres: [], isFollowing: false },
    support: support.map((name, i) => ({
      id: `${id}-s${i}`,
      name,
      genres: [],
      isFollowing: false,
    })),
    userAdded: false,
  }) as GigSummary;

const mockMine = jest.fn();
const mockGigs = jest.fn();
const mockArtists = jest.fn();
const mockAddArtist = jest.fn();
jest.mock('@/api/generated/catalog/catalog', () => ({
  useListMyGigs: (...args: unknown[]) => mockMine(...args),
  useListGigs: (...args: unknown[]) => mockGigs(...args),
  useListArtists: (...args: unknown[]) => mockArtists(...args),
  useSearchVenues: () => ({ data: [] }),
  useSuggest: () => ({ data: [] }),
  addArtist: (...args: unknown[]) => mockAddArtist(...args),
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
// The add-a-gig form has its own flow; here it adds a fixed gig at once.
jest.mock('./AddGigForm', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    AddGigForm: ({ onAdded }: { onAdded: (g: unknown) => void }) =>
      React.createElement(
        Text,
        {
          onPress: () =>
            onAdded({
              id: 'new',
              title: 'Pub Gig',
              startsAt: '2026-09-25T19:00:00Z',
              venue: { id: 'v2', name: 'The Snug', city: 'Manchester' },
              headliner: { id: 'new-h', name: 'Pub Band', genres: [], isFollowing: false },
              support: [],
              userAdded: true,
            }),
        },
        'add-gig-stub',
      ),
  };
});

function Harness({ onChange }: { onChange: (g: GigSummary | null) => void }) {
  const [value, setValue] = useState<GigSummary | null>(null);
  return (
    <ThemeProvider>
      <GigPicker
        value={value}
        onChange={(next) => {
          onChange(next);
          setValue(next);
        }}
      />
    </ThemeProvider>
  );
}

describe('GigPicker (the first step of a post)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockMine.mockReturnValue({
      isPending: false,
      data: { items: [gig('g1', 'Went To', ['Opener'])] },
    });
    mockGigs.mockImplementation((params: { q?: string }) =>
      params.q
        ? { isFetching: false, data: { items: [gig('g9', 'Found By Search')] } }
        : { isPending: false, data: { items: [gig('g2', 'Nearby')] } },
    );
    mockArtists.mockReturnValue({ data: { items: [] } });
  });

  it('suggests recent gigs; choosing one shows it with a way to change it', async () => {
    const onChange = jest.fn();
    await render(<Harness onChange={onChange} />);

    await fireEvent.press(screen.getByLabelText('Went To · The Venue'));

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ id: 'g1' }));
    await fireEvent.press(screen.getByText('Change gig'));
    expect(onChange).toHaveBeenLastCalledWith(null);
    expect(screen.getByLabelText('Nearby · The Venue')).toBeTruthy();
  });

  it('searches every gig of the past year, newest first and UK-wide', async () => {
    await render(<Harness onChange={jest.fn()} />);

    await fireEvent.changeText(screen.getByLabelText('Search for the gig'), 'found');

    await waitFor(() => expect(screen.getByLabelText('Found By Search · The Venue')).toBeTruthy());
    const searched = mockGigs.mock.calls
      .map(([p]) => p as Record<string, unknown>)
      .find((p) => p.q);
    expect(searched).toMatchObject({ q: 'found', order: 'desc', nationwide: true });
  });

  it('adds a gig that is not listed and selects it', async () => {
    const onChange = jest.fn();
    await render(<Harness onChange={onChange} />);

    await fireEvent.press(screen.getByTestId('add-gig'));
    await fireEvent.press(screen.getByText('add-gig-stub'));

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ title: 'Pub Gig' }));
  });
});
