import { useSearch, useSuggest } from '@/api/generated/catalog/catalog';
import type { GigSummary, PersonResult, Suggestion } from '@/api/generated/model';
import { useSearchPeople } from '@/api/generated/social/social';
import { useDistanceOrigin } from '@/features/location/deviceLocation';

import type { Group, ParsedQuery, TopNames } from './searchQuery';

/** One group's state; each group loads and fails on its own, so one slow server never blanks the rest. */
export type GroupState<T> = {
  status: 'idle' | 'loading' | 'error' | 'done';
  items: T[];
  retry: () => void;
};

export type SearchResults = {
  people: GroupState<PersonResult>;
  artists: GroupState<Suggestion>;
  venues: GroupState<Suggestion>;
  upcoming: GroupState<GigSummary>;
  past: GroupState<GigSummary>;
};

type Query<T> = {
  data: T | undefined;
  isPending: boolean;
  isError: boolean;
  refetch: () => unknown;
};

function stateOf<T, R>(query: Query<T>, enabled: boolean, pick: (data: T) => R[]): GroupState<R> {
  const retry = () => void query.refetch();
  if (!enabled) return { status: 'idle', items: [], retry };
  if (query.data !== undefined) return { status: 'done', items: pick(query.data), retry };
  if (query.isError) return { status: 'error', items: [], retry };
  return { status: 'loading', items: [], retry };
}

/**
 * The Search tab asks three servers at once, each the owner of its data: catalog's suggestions
 * (artists and venues, ranked by name then by the viewer), social's people, and catalog's upcoming
 * and past gigs (distances from the phone when allowed). With an @ only people are asked.
 */
export function useSearchResults(query: ParsedQuery): SearchResults {
  const origin = useDistanceOrigin();
  const q = query.text;
  const catalogWanted = query.ready && !query.peopleOnly;
  const gigsWanted = catalogWanted && origin.ready;
  const suggest = useSuggest({ q }, { query: { enabled: catalogWanted } });
  const people = useSearchPeople({ q }, { query: { enabled: query.peopleReady } });
  const gigs = useSearch(
    { q, types: 'gigs,pastGigs', ...origin.params },
    { query: { enabled: gigsWanted } },
  );
  return {
    people: stateOf(people, query.peopleReady, (data) => data),
    artists: stateOf(suggest, catalogWanted, (data) => data.filter((s) => s.kind === 'ARTIST')),
    venues: stateOf(suggest, catalogWanted, (data) => data.filter((s) => s.kind === 'VENUE')),
    upcoming: stateOf(gigs, catalogWanted, (data) => data.gigs),
    past: stateOf(gigs, catalogWanted, (data) => data.pastGigs),
  };
}

/** The best-ranked names per group, for `groupsToShow`'s exact-match rule. */
export function topNames(results: SearchResults): TopNames {
  const first = results.people.items[0];
  return {
    people: first ? [first.user.handle, first.user.displayName] : [],
    artists: results.artists.items.slice(0, 1).map((s) => s.name),
    venues: results.venues.items.slice(0, 1).map((s) => s.name),
  };
}

/** The groups among these that were asked: people wait for a third character. */
export function asked(results: SearchResults, groups: Group[]): Group[] {
  return groups.filter((g) => results[g].status !== 'idle');
}

/** Whether every group asked has answered, and none has anything. */
export function nothingFound(results: SearchResults, groups: Group[]): boolean {
  const shown = asked(results, groups);
  return (
    shown.length > 0 &&
    shown.every((g) => results[g].status === 'done' && results[g].items.length === 0)
  );
}

/** Whether every group asked has failed. */
export function allFailed(results: SearchResults, groups: Group[]): boolean {
  const shown = asked(results, groups);
  return shown.length > 0 && shown.every((g) => results[g].status === 'error');
}
