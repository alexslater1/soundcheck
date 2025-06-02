import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import type { GigSummary } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { ComposeForm } from './ComposeForm';

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

async function renderForm(props: Partial<React.ComponentProps<typeof ComposeForm>> = {}) {
  const onPost = jest.fn();
  await render(
    <ThemeProvider>
      <ComposeForm onPost={onPost} {...props} />
    </ThemeProvider>,
  );
  return onPost;
}

describe('ComposeForm', () => {
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

  it('suggests recent gigs; choosing one tags its whole bill; an act can be unticked', async () => {
    const onPost = await renderForm();

    await fireEvent.press(screen.getByLabelText('Went To · The Venue'));
    await fireEvent.changeText(screen.getByLabelText('Caption'), 'What a night');
    await fireEvent.press(screen.getByText('Opener'));
    await fireEvent.press(screen.getByText('Post'));

    expect(onPost).toHaveBeenCalledWith(
      { caption: 'What a night', gigId: 'g1', artistIds: ['g1-h'] },
      expect.objectContaining({ id: 'g1' }),
    );
  });

  it('searches every gig of the past year, newest first and UK-wide', async () => {
    await renderForm();

    await fireEvent.changeText(screen.getByLabelText('Search for the gig'), 'found');

    await waitFor(() => expect(screen.getByLabelText('Found By Search · The Venue')).toBeTruthy());
    const searched = mockGigs.mock.calls
      .map(([p]) => p as Record<string, unknown>)
      .find((p) => p.q);
    expect(searched).toMatchObject({ q: 'found', order: 'desc', nationwide: true });
  });

  it('adds a gig that is not listed and selects it', async () => {
    const onPost = await renderForm();

    await fireEvent.press(screen.getByTestId('add-gig'));
    await fireEvent.press(screen.getByText('add-gig-stub'));
    await fireEvent.press(screen.getByText('Post'));

    expect(onPost).toHaveBeenCalledWith(
      { caption: '', gigId: 'new', artistIds: ['new-h'] },
      expect.objectContaining({ title: 'Pub Gig' }),
    );
  });

  it('tags another act found by name, keeping it when the gig changes', async () => {
    mockArtists.mockReturnValue({
      data: { items: [{ id: 'x1', name: 'Guest Star', genres: [], isFollowing: false }] },
    });
    const onPost = await renderForm({ initialGig: gig('g1', 'Went To') });

    await fireEvent.changeText(screen.getByLabelText('Tag another act'), 'guest');
    await fireEvent.press(await screen.findByText('+ Guest Star'));
    await fireEvent.press(screen.getByText('Change gig'));
    await fireEvent.press(screen.getByLabelText('Nearby · The Venue'));
    await fireEvent.press(screen.getByText('Post'));

    expect(onPost.mock.calls[0]![0]).toEqual({
      caption: '',
      gigId: 'g2',
      artistIds: ['g2-h', 'x1'],
    });
  });

  it('labels the button with the number of clips', async () => {
    await renderForm({ clips: 3 });
    expect(screen.getByText('Post 3 clips')).toBeTruthy();
  });
});
