import { groupsToShow, normaliseName, parseQuery } from './searchQuery';

describe('parseQuery', () => {
  it('trims, and searches from two characters', () => {
    expect(parseQuery('  oasis ')).toEqual({ text: 'oasis', peopleOnly: false, ready: true });
    expect(parseQuery('o')).toEqual({ text: 'o', peopleOnly: false, ready: false });
  });

  it('treats a leading @ as a handle: people only, sent without the @', () => {
    expect(parseQuery('@alex_s')).toEqual({ text: 'alex_s', peopleOnly: true, ready: true });
    expect(parseQuery('@a')).toEqual({ text: 'a', peopleOnly: true, ready: false });
  });
});

describe('normaliseName', () => {
  it('ignores case, spacing, a leading "the" and a leading @', () => {
    expect(normaliseName('The  North')).toBe('north');
    expect(normaliseName('@Alex_S')).toBe('alex_s');
  });
});

describe('groupsToShow', () => {
  const all = (raw: string, tops = {}) => groupsToShow(parseQuery(raw), 'all', tops);

  it('keeps the default order when nothing matches exactly', () => {
    expect(all('oas', { artists: ['Oasis'] })).toEqual([
      'artists',
      'people',
      'venues',
      'upcoming',
      'past',
    ]);
  });

  it('puts a group whose best result is exactly what was typed first', () => {
    expect(all('alex_s', { artists: ['Alexisonfire'], people: ['alex_s', 'Alex Slater'] })).toEqual(
      ['people', 'artists', 'venues', 'upcoming', 'past'],
    );
    expect(all('Alex Slater', { people: ['alex_s', 'Alex Slater'] })[0]).toBe('people');
    expect(all('north', { artists: ['The North'], venues: ['North Bar'] })[0]).toBe('artists');
    expect(all('albert hall', { venues: ['Albert Hall'] })[0]).toBe('venues');
  });

  it('shows only the filtered group, and both gig groups under Gigs', () => {
    const q = parseQuery('oasis');
    expect(groupsToShow(q, 'venues', {})).toEqual(['venues']);
    expect(groupsToShow(q, 'gigs', {})).toEqual(['upcoming', 'past']);
  });

  it('shows only people for an @ whatever the filter', () => {
    expect(groupsToShow(parseQuery('@oasis'), 'artists', { artists: ['Oasis'] })).toEqual([
      'people',
    ]);
  });
});
