import { fireEvent, render, screen } from '@testing-library/react-native';

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

const mockArtists = jest.fn();
const mockAddArtist = jest.fn();
jest.mock('@/api/generated/catalog/catalog', () => ({
  useListArtists: (...args: unknown[]) => mockArtists(...args),
  useSearchVenues: () => ({ data: [] }),
  useSuggest: () => ({ data: [] }),
  addArtist: (...args: unknown[]) => mockAddArtist(...args),
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
async function renderForm(props: Partial<React.ComponentProps<typeof ComposeForm>> = {}) {
  const onPost = jest.fn();
  const view = await render(
    <ThemeProvider>
      <ComposeForm onPost={onPost} gig={null} {...props} />
    </ThemeProvider>,
  );
  return { onPost, view };
}

describe('ComposeForm', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockArtists.mockReturnValue({ data: { items: [] } });
  });

  it("tags the chosen gig's whole bill; an act can be unticked", async () => {
    const { onPost } = await renderForm({ gig: gig('g1', 'Went To', ['Opener']) });

    await fireEvent.changeText(screen.getByLabelText('Caption'), 'What a night');
    await fireEvent.press(screen.getByText('Opener'));
    await fireEvent.press(screen.getByText('Post'));

    expect(onPost).toHaveBeenCalledWith({
      caption: 'What a night',
      gigId: 'g1',
      artistIds: ['g1-h'],
    });
  });

  it('tags another act found by name, keeping it and the caption when the gig changes', async () => {
    mockArtists.mockReturnValue({
      data: { items: [{ id: 'x1', name: 'Guest Star', genres: [], isFollowing: false }] },
    });
    const { onPost, view } = await renderForm({ gig: gig('g1', 'Went To') });

    await fireEvent.changeText(screen.getByLabelText('Tag another act'), 'guest');
    await fireEvent.press(await screen.findByText('+ Guest Star'));
    await fireEvent.changeText(screen.getByLabelText('Caption'), 'Encore');
    await view.rerender(
      <ThemeProvider>
        <ComposeForm onPost={onPost} gig={gig('g2', 'Nearby')} />
      </ThemeProvider>,
    );
    await fireEvent.press(screen.getByText('Post'));

    expect(onPost.mock.calls[0]![0]).toEqual({
      caption: 'Encore',
      gigId: 'g2',
      artistIds: ['g2-h', 'x1'],
    });
  });

  it('posts without a gig when none was chosen', async () => {
    const { onPost } = await renderForm();
    await fireEvent.press(screen.getByText('Post'));
    expect(onPost).toHaveBeenCalledWith({ caption: '', gigId: null, artistIds: [] });
  });

  it('labels the button with the number of clips', async () => {
    await renderForm({ clips: 3 });
    expect(screen.getByText('Post 3 clips')).toBeTruthy();
  });
});
