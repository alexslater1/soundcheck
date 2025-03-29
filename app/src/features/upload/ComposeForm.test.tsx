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
    venue: { id: 'v1', name: 'The Venue' },
    headliner: { id: `${id}-h`, name: `${title} headliner`, genres: [], isFollowing: false },
    support: support.map((name, i) => ({
      id: `${id}-s${i}`,
      name,
      genres: [],
      isFollowing: false,
    })),
  }) as GigSummary;

const mockAttended = jest.fn();
const mockNearby = jest.fn();
jest.mock('@/api/generated/catalog/catalog', () => ({
  useListMyGigs: (...args: unknown[]) => mockAttended(...args),
  useListGigs: (...args: unknown[]) => mockNearby(...args),
}));

async function renderForm(onPost = jest.fn()) {
  await render(
    <ThemeProvider>
      <ComposeForm onPost={onPost} />
    </ThemeProvider>,
  );
  return onPost;
}

describe('ComposeForm', () => {
  beforeEach(() => {
    mockAttended.mockReturnValue({
      isPending: false,
      data: { items: [gig('g1', 'Went To', ['Opener'])] },
    });
    mockNearby.mockReturnValue({ isPending: false, data: { items: [gig('g2', 'Nearby')] } });
  });

  it('posts the caption with the chosen gig and its whole bill by default', async () => {
    const onPost = await renderForm();

    await fireEvent.changeText(screen.getByLabelText('Caption'), 'What a night');
    await fireEvent.press(screen.getByText('Went To · The Venue'));
    await fireEvent.press(screen.getByText('Post'));

    expect(onPost).toHaveBeenCalledWith({
      caption: 'What a night',
      gigId: 'g1',
      artistIds: ['g1-h', 'g1-s0'],
    });
  });

  it('lets an artist be unticked, and asks for attended gigs and recent nearby ones', async () => {
    const onPost = await renderForm();
    expect(mockAttended).toHaveBeenCalledWith({ when: 'past', limit: 5 });
    expect(mockNearby).toHaveBeenCalledWith(
      expect.objectContaining({ limit: 8, from: expect.any(String) }),
    );

    await fireEvent.press(screen.getByText('Went To · The Venue'));
    await fireEvent.press(screen.getByText('Opener'));
    await fireEvent.press(screen.getByText('Post'));

    expect(onPost).toHaveBeenCalledWith({ caption: '', gigId: 'g1', artistIds: ['g1-h'] });
  });

  it('posts without a gig when none is chosen, and says so when there are none to choose', async () => {
    mockAttended.mockReturnValue({ isPending: false, data: { items: [] } });
    mockNearby.mockReturnValue({ isPending: false, data: { items: [] } });
    const onPost = await renderForm();

    expect(screen.getByTestId('compose-no-gigs')).toBeTruthy();
    await fireEvent.press(screen.getByText('Post'));

    expect(onPost).toHaveBeenCalledWith({ caption: '', gigId: null, artistIds: [] });
  });
});
