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
