import { act, fireEvent, render, screen, within } from '@testing-library/react-native';
import type { ReactNode } from 'react';

import type { GigSummary, PersonResult, Suggestion } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { SearchScreen } from './SearchScreen';

type Answer<T> = { data?: T; isPending: boolean; isError: boolean; refetch: jest.Mock };
const ok = <T,>(data: T): Answer<T> => ({
  data,
  isPending: false,
  isError: false,
  refetch: jest.fn(),
});
const failed = <T,>(): Answer<T> => ({ isPending: false, isError: true, refetch: jest.fn() });

const mockAnswers: {
  suggest: Answer<Suggestion[]>;
  people: Answer<PersonResult[]>;
  gigs: Answer<{ artists: []; gigs: GigSummary[]; pastGigs: GigSummary[] }>;
} = { suggest: ok([]), people: ok([]), gigs: ok({ artists: [], gigs: [], pastGigs: [] }) };
/** What each hook was last asked, and whether it was enabled. */
const mockAsked: Record<string, { params: unknown; enabled: boolean }> = {};

jest.mock('@/api/generated/catalog/catalog', () => ({
  useSuggest: (params: unknown, options: { query: { enabled: boolean } }) => {
    mockAsked.suggest = { params, enabled: options.query.enabled };
    return mockAnswers.suggest;
  },
  useSearch: (params: unknown, options: { query: { enabled: boolean } }) => {
    mockAsked.gigs = { params, enabled: options.query.enabled };
    return mockAnswers.gigs;
  },
}));
jest.mock('@/api/generated/social/social', () => ({
  useSearchPeople: (params: unknown, options: { query: { enabled: boolean } }) => {
    mockAsked.people = { params, enabled: options.query.enabled };
    return mockAnswers.people;
  },
}));
jest.mock('@/features/location/deviceLocation', () => ({
  useDistanceOrigin: () => ({
    params: { fromLat: 53.48, fromLng: -2.24 },
    ready: true,
    here: null,
  }),
}));
const mockPush = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
  Link: ({ children }: { children: ReactNode }) => children,
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('@/features/gigs/GigCard', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    GigCard: ({ gig }: { gig: { title: string } }) =>
      React.createElement(Text, { testID: 'gig-card' }, gig.title),
  };
});

const artist = (name: string, id = name): Suggestion => ({
  kind: 'ARTIST',
  id,
  name,
  city: null,
  imageUrl: null,
  upcomingGigs: 2,
});
const venue = (name: string): Suggestion => ({
  kind: 'VENUE',
  id: name,
  name,
  city: 'Manchester',
  imageUrl: null,
  upcomingGigs: 0,
});
const person = (handle: string, displayName: string, isPrivate = false): PersonResult => ({
  user: { id: handle, handle, displayName, avatarUrl: null, isFollowing: false },
  profileVisibility: isPrivate ? 'PRIVATE' : 'PUBLIC',
});
const gig = (title: string) => ({ id: title, title }) as unknown as GigSummary;

async function type(text: string) {
  await fireEvent.changeText(screen.getByTestId('search-input'), text);
  await act(async () => {
    jest.advanceTimersByTime(300);
  });
}

/** The group sections in the order shown. */
const order = () =>
  screen
    .getAllByTestId(/^search-group-/)
    .map((node) => String(node.props.testID).replace('search-group-', ''));

describe('SearchScreen', () => {
  beforeEach(() => {
    jest.useFakeTimers({ advanceTimers: true });
    mockPush.mockClear();
    mockAnswers.suggest = ok([]);
    mockAnswers.people = ok([]);
    mockAnswers.gigs = ok({ artists: [], gigs: [], pastGigs: [] });
  });
  afterEach(() => jest.useRealTimers());

  async function show() {
    await render(
      <ThemeProvider>
        <SearchScreen />
      </ThemeProvider>,
    );
  }

  it('explains itself and asks nothing until two characters are typed', async () => {
    await show();
    expect(screen.getByTestId('search-hint')).toBeTruthy();
    await type('o');
    expect(screen.getByTestId('search-hint')).toBeTruthy();
    expect(mockAsked.suggest?.enabled).toBe(false);
    expect(mockAsked.people?.enabled).toBe(false);
    expect(mockAsked.gigs?.enabled).toBe(false);
  });

  it('groups the results, upcoming and past gigs apart, the exact match first', async () => {
    mockAnswers.suggest = ok([artist('Oasis'), venue('Oasis Bar')]);
    mockAnswers.people = ok([person('oasisfan', 'Liam')]);
    mockAnswers.gigs = ok({
      artists: [],
      gigs: [gig('Oasis Live 26')],
      pastGigs: [gig('Oasis 25')],
    });
    await show();
    await type('oasis');

    expect(mockAsked.gigs?.params).toEqual({
      q: 'oasis',
      types: 'gigs,pastGigs',
      fromLat: 53.48,
      fromLng: -2.24,
    });
    expect(order()).toEqual(['artists', 'people', 'venues', 'upcoming', 'past']);
    expect(
      within(screen.getByTestId('search-group-upcoming')).getByText('Oasis Live 26'),
    ).toBeTruthy();
    expect(within(screen.getByTestId('search-group-past')).getByText('Oasis 25')).toBeTruthy();

    // A person's exact handle puts People first.
    mockAnswers.people = ok([person('oasis', 'Oasis Fan Club')]);
    mockAnswers.suggest = ok([artist('Oasis Tribute')]);
    await type('Oasis ');
    expect(order()[0]).toBe('people');
  });

  it('opens the clips of a picked artist', async () => {
    mockAnswers.suggest = ok([artist('Oasis', 'a1')]);
    await show();
    await type('oasis');
    await fireEvent.press(screen.getByTestId('search-artist'));
    expect(mockPush).toHaveBeenCalledWith({
      pathname: '/search/[kind]/[id]',
      params: { kind: 'artist', id: 'a1', name: 'Oasis' },
    });
  });

  it('searches only people after an @ and marks private profiles', async () => {
    mockAnswers.people = ok([person('alex_s', 'Alex', true)]);
    await show();
    await type('@alex');
    expect(mockAsked.people).toEqual({ params: { q: 'alex' }, enabled: true });
    expect(mockAsked.suggest?.enabled).toBe(false);
    expect(mockAsked.gigs?.enabled).toBe(false);
    expect(order()).toEqual(['people']);
    expect(screen.queryByText('Artists')).toBeNull(); // no chips
    expect(screen.getByTestId('search-person-private')).toBeTruthy();
  });

  it('shows three per group under All, and the rest under the group chip', async () => {
    mockAnswers.people = ok(['a1', 'a2', 'a3', 'a4', 'a5'].map((h) => person(h, h)));
    await show();
    await type('al');
    expect(screen.getAllByTestId('search-person')).toHaveLength(3);
    await fireEvent.press(screen.getByText('More'));
    expect(screen.getAllByTestId('search-person')).toHaveLength(5);
    expect(order()).toEqual(['people']);
  });

  it('says when nothing matches, and when the search failed', async () => {
    await show();
    await type('zzzz');
    expect(screen.getByTestId('search-empty')).toBeTruthy();

    mockAnswers.suggest = failed();
    mockAnswers.people = failed();
    mockAnswers.gigs = failed();
    await type('zzzzz');
    expect(screen.getByTestId('search-error')).toBeTruthy();
  });

  it('keeps the groups that answered when one fails, with a retry for that one', async () => {
    mockAnswers.suggest = ok([artist('Oasis')]);
    mockAnswers.people = failed();
    await show();
    await type('oasis');
    expect(screen.getByTestId('search-artist')).toBeTruthy();
    await fireEvent.press(
      within(screen.getByTestId('search-group-people')).getByText(/Could not load/),
    );
    expect(mockAnswers.people.refetch).toHaveBeenCalled();
  });
});
