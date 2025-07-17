/**
 * The Search tab's rules, kept free of React so they are tested on their own:
 * what was typed, which groups to ask for, and in which order to show them.
 */

/** The result groups, in their default order: artists first, since this is a music app. */
export const GROUPS = ['artists', 'people', 'venues', 'upcoming', 'past'] as const;
export type Group = (typeof GROUPS)[number];

/** The filter chips. "Gigs" shows both gig groups, upcoming and past. */
export const FILTERS = ['all', 'people', 'artists', 'venues', 'gigs'] as const;
export type Filter = (typeof FILTERS)[number];

export const FILTER_LABELS: Record<Filter, string> = {
  all: 'All',
  people: 'People',
  artists: 'Artists',
  venues: 'Venues',
  gigs: 'Gigs',
};

/** Fewer characters than this ask nothing: one letter matches half the catalog. */
export const MIN_LENGTH = 2;

/**
 * People need one more: a handle has at least three, and the server's trigram index cannot narrow a
 * match on fewer.
 */
export const MIN_PEOPLE_LENGTH = 3;

export type ParsedQuery = {
  /** What to send: trimmed, without a leading @. */
  text: string;
  /** A leading @ means a handle: only people are searched. */
  peopleOnly: boolean;
  /** Long enough to search artists, venues and gigs. */
  ready: boolean;
  /** Long enough to search people. */
  peopleReady: boolean;
};

export function parseQuery(raw: string): ParsedQuery {
  const trimmed = raw.trim();
  const peopleOnly = trimmed.startsWith('@');
  const text = (peopleOnly ? trimmed.slice(1) : trimmed).trim();
  const peopleReady = text.length >= MIN_PEOPLE_LENGTH;
  return {
    text,
    peopleOnly,
    ready: peopleOnly ? peopleReady : text.length >= MIN_LENGTH,
    peopleReady,
  };
}

/**
 * A name as the server compares it for an exact match: lower case, single spaces, no leading "the "
 * (as the catalog's normaliser: "The North" and "north" are the same act) and no leading @.
 */
export function normaliseName(name: string): string {
  return name.trim().toLowerCase().replace(/^@/, '').replace(/\s+/g, ' ').replace(/^the /, '');
}

/** The best-ranked name in each group, for the exact-match rule; a person has two names. */
export type TopNames = Partial<Record<Group, readonly string[]>>;

/**
 * The groups to show, in order. With a filter, only its groups. Otherwise every group, and a group whose
 * best result is exactly what was typed comes first ("oasis" puts Artists on top, "alex_s" puts People
 * on top), the rest staying in the default order. That is the "works out what you mean" rule: each
 * server ranks within its own kind, and only the client sees all the kinds at once.
 */
export function groupsToShow(query: ParsedQuery, filter: Filter, tops: TopNames): Group[] {
  if (query.peopleOnly) {
    return ['people'];
  }
  if (filter === 'gigs') {
    return ['upcoming', 'past'];
  }
  if (filter !== 'all') {
    return [filter];
  }
  const typed = normaliseName(query.text);
  const exact = (group: Group) => (tops[group] ?? []).some((name) => normaliseName(name) === typed);
  return [...GROUPS.filter(exact), ...GROUPS.filter((group) => !exact(group))];
}

export const GROUP_TITLES: Record<Group, string> = {
  artists: 'Artists',
  people: 'People',
  venues: 'Venues',
  upcoming: 'Upcoming gigs',
  past: 'Past gigs',
};
