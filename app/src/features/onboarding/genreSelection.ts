/** Pure state for the genre picker (`PUT /me/genres`: 1–8 genres, weights in [0, 1]). */
export const MAX_GENRES = 8;
export const MIN_GENRES = 1;
export const WEIGHTS = [0.5, 1] as const;

export type GenreSelection = ReadonlyMap<number, number>; // genreId → weight

export function toggleGenre(selection: GenreSelection, genreId: number): GenreSelection {
  const next = new Map(selection);
  if (next.has(genreId)) {
    next.delete(genreId);
  } else if (next.size < MAX_GENRES) {
    next.set(genreId, 1);
  }
  return next;
}

export function setWeight(
  selection: GenreSelection,
  genreId: number,
  weight: number,
): GenreSelection {
  if (!selection.has(genreId) || weight < 0 || weight > 1) return selection;
  const next = new Map(selection);
  next.set(genreId, weight);
  return next;
}

export function canContinue(selection: GenreSelection): boolean {
  return selection.size >= MIN_GENRES && selection.size <= MAX_GENRES;
}

export function toRequest(selection: GenreSelection): { genreId: number; weight: number }[] {
  return [...selection.entries()].map(([genreId, weight]) => ({ genreId, weight }));
}

export function fromMe(genres: { genreId: number; weight: number }[]): GenreSelection {
  return new Map(genres.map((g) => [g.genreId, g.weight]));
}

/** What the genre search needs of a genre (`GET /genres`). */
export type SearchableGenre = { id: number; name: string; aliases: string[] };

/** A genre that matches a search, and the other name it matched by, if it was not its own name. */
export type GenreMatch<G extends SearchableGenre> = { genre: G; via: string | null };

/**
 * The genre search of the sign-up picker. The list is the 24 canonical genres the ranking
 * understands, so a search for a style that is not one of them ("shoegaze", "dnb") finds the genre it
 * belongs to through its aliases and says so. Order: name starts with the query, name contains it, an
 * alias starts with it, an alias contains it; the list's own order within each.
 */
export function searchGenres<G extends SearchableGenre>(
  genres: G[],
  query: string,
): GenreMatch<G>[] {
  const q = query.trim().toLowerCase();
  if (q === '') {
    return genres.map((genre) => ({ genre, via: null }));
  }
  const ranked: { match: GenreMatch<G>; rank: number; index: number }[] = [];
  genres.forEach((genre, index) => {
    const name = genre.name.toLowerCase();
    if (name.startsWith(q) || name.includes(q)) {
      ranked.push({ match: { genre, via: null }, rank: name.startsWith(q) ? 0 : 1, index });
      return;
    }
    const prefix = genre.aliases.find((alias) => alias.startsWith(q));
    const contains = prefix ?? genre.aliases.find((alias) => alias.includes(q));
    if (contains) {
      ranked.push({ match: { genre, via: contains }, rank: prefix ? 2 : 3, index });
    }
  });
  return ranked.sort((a, b) => a.rank - b.rank || a.index - b.index).map((r) => r.match);
}
