import { canContinue, searchGenres, setWeight, toggleGenre, toRequest } from './genreSelection';

describe('genre selection', () => {
  it('toggles, caps at 8 and needs at least one to continue', () => {
    let s = toggleGenre(new Map(), 2);
    expect(s.get(2)).toBe(1);
    expect(canContinue(s)).toBe(true);
    for (const id of [3, 4, 5, 6, 7, 8, 9]) s = toggleGenre(s, id);
    expect(s.size).toBe(8);
    s = toggleGenre(s, 10); // ninth is ignored
    expect(s.size).toBe(8);
    s = toggleGenre(s, 2);
    expect(s.has(2)).toBe(false);
    expect(canContinue(new Map())).toBe(false);
  });

  it('adjusts weights only for selected genres within [0, 1]', () => {
    let s = toggleGenre(new Map(), 14);
    s = setWeight(s, 14, 0.5);
    expect(s.get(14)).toBe(0.5);
    expect(setWeight(s, 99, 0.5)).toBe(s);
    expect(setWeight(s, 14, 2)).toBe(s);
    expect(toRequest(s)).toEqual([{ genreId: 14, weight: 0.5 }]);
  });

  describe('searchGenres', () => {
    const genres = [
      { id: 2, name: 'Indie', aliases: ['indie rock', 'britpop', 'shoegaze'] },
      { id: 4, name: 'Punk', aliases: ['post-punk', 'pop punk'] },
      { id: 6, name: 'Pop', aliases: ['synthpop', 'k-pop'] },
      { id: 15, name: 'Drum and Bass', aliases: ['dnb', 'jungle'] },
    ];
    const names = (q: string) => searchGenres(genres, q).map((m) => [m.genre.name, m.via]);

    it('lists every genre for an empty query', () => {
      expect(names('  ')).toHaveLength(4);
    });

    it('finds a style by its other name and says which', () => {
      expect(names('shoe')).toEqual([['Indie', 'shoegaze']]);
      expect(names('DNB')).toEqual([['Drum and Bass', 'dnb']]);
    });

    it('puts names before aliases, and prefixes before matches inside', () => {
      // "pop": Pop (its name) → Punk ("pop punk" starts with it) → Indie ("britpop" contains it).
      expect(names('pop')).toEqual([
        ['Pop', null],
        ['Punk', 'pop punk'],
        ['Indie', 'britpop'],
      ]);
      expect(names('bass')).toEqual([['Drum and Bass', null]]);
    });

    it('finds nothing for a word no genre has', () => {
      expect(names('polka')).toEqual([]);
    });
  });
});
