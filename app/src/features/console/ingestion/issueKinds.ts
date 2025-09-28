import type { IngestionIssue, IngestionRun } from '@/api/generated/model';

/**
 * One line per issue kind the backend records (the SourceIssue kinds in `backend/.../ingestion`; a venue
 * site's fetch outcome arrives as VENUE_FAILED, VENUE_BLOCKED or VENUE_DISALLOWED), for the operator who
 * reads a run's issues. A kind missing here is shown as its raw name, so a new kind never hides: it just
 * arrives without an explanation.
 */
export const ISSUE_KINDS: Readonly<Record<string, string>> = {
  VENUE_EMPTY: "A venue's own site listed no shows: its page may have changed shape.",
  VENUE_UNREADABLE:
    "A venue's own site answered, but its page couldn't be parsed: its markup may have changed.",
  VENUE_FAILED: "A venue's site answered with an error or timed out; the next run tries again.",
  VENUE_BLOCKED: "A venue's site refused us (403 or 429): it is left alone for a day.",
  VENUE_DISALLOWED: "A venue's robots.txt doesn't allow the page, so it isn't read.",
  VENUE_NO_ADAPTER:
    "A venue in the registry names an adapter that doesn't exist (a configuration slip).",
  NOT_SEEN:
    'An upcoming gig the source has left out of its last two complete runs (flagged, not deleted).',
  NO_ARTISTS: 'An event without a line-up: skipped, since a gig needs at least one act.',
  NO_DATE: 'An event without a usable date: skipped.',
  NO_VENUE: 'An event without a venue: skipped.',
  NO_ID_OR_NAME: 'An event without an id or a name: skipped.',
  MARKET_SPLIT_INCOMPLETE:
    "A busy day was split by Ticketmaster's regional markets, and the markets add up to fewer events than the day.",
  WINDOW_TRUNCATED:
    "One market's day had more events than Ticketmaster lets us page through; the rest were missed.",
  FIXTURE_DATES_SHIFTED:
    'Fixtures mode: the recorded dates were moved forward so they start today.',
  SETLISTFM_DAILY_CAP:
    "setlist.fm's daily request allowance ran out; the backfill resumes tomorrow.",
  SETLISTFM_PAGE_CAP:
    'An act had more setlists in the window than the per-act page cap; the older ones were not read.',
  BUDGET_EXHAUSTED: 'The run spent its API-call budget and stopped early (status PARTIAL).',
  VENUE_OUTSIDE_UK: "The venue's coordinates are outside the UK: probably a geocoding mistake.",
  START_IN_PAST: 'An upcoming-gigs source listed a gig that has already started.',
  MISSING_COORDINATES:
    'The venue came without coordinates; the geocoder tries its postcode, then its town.',
  ENRICHMENT_FAILED: "One act's MusicBrainz or Last.fm lookup failed; the next run retries it.",
  ENRICHMENT_ABORTED:
    'Enriching acts stopped early; the gigs are stored and the next run carries on.',
  GEOCODING_ABORTED: 'Locating venues stopped early; the next run carries on.',
  DUPLICATE_EXTERNAL_ID:
    'The source listed the same event twice in one run; the second was skipped.',
  ARTIST_NO_GENRES: 'An act came without genres, so the feed has less to match it on.',
  INGEST_ERROR: "One gig couldn't be stored (it rolled back alone); the rest of the run went on.",
};

/**
 * Where an operator can act on an issue kind, for the few that have a place. The rest need nothing: a
 * skipped non-music event, an act without genres, a site that failed are all retried by the next run.
 */
export const ISSUE_ACTIONS: Readonly<Record<string, { label: string; catalogTab: string }>> = {
  MISSING_COORDINATES: {
    label: 'Venues the geocoder could only place by town: Catalog → Venues → Suspect locations',
    catalogTab: 'venues',
  },
};

/** The run's near misses (listings almost matched to a gig) are decided in Catalog → Matches. */
export function nearMissLine(flagged: number): string {
  return `${flagged} near ${flagged === 1 ? 'miss' : 'misses'} to decide: Catalog → Matches`;
}

/** The explanation for a kind, or undefined for one this table doesn't know (shown raw). */
export function explainIssue(kind: string): string | undefined {
  return Object.prototype.hasOwnProperty.call(ISSUE_KINDS, kind) ? ISSUE_KINDS[kind] : undefined;
}

export type IssueGroup = {
  kind: string;
  explanation: string | undefined;
  issues: IngestionIssue[];
};

/** A run's stored issues grouped by kind, the biggest group first (ties by name). */
export function groupIssues(issues: readonly IngestionIssue[]): IssueGroup[] {
  const byKind = new Map<string, IngestionIssue[]>();
  for (const issue of issues) {
    const group = byKind.get(issue.kind);
    if (group) group.push(issue);
    else byKind.set(issue.kind, [issue]);
  }
  return [...byKind.entries()]
    .map(([kind, list]) => ({ kind, explanation: explainIssue(kind), issues: list }))
    .sort((a, b) => b.issues.length - a.issues.length || a.kind.localeCompare(b.kind));
}

/** A run's issue counts (all of them, not just the stored sample) as [kind, count], biggest first. */
export function issueCountList(counts: Readonly<Record<string, number>>): [string, number][] {
  return Object.entries(counts).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
}

/** QUEUED or RUNNING: the source can't take another run until it ends (one active run per source). */
export function isActive(status: string | undefined): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

/**
 * The kinds that mean a venue's own site has probably broken. There is no `layoutChanged` flag: an
 * empty or unreadable site is how breakage shows up.
 */
export const BREAKAGE_KINDS = ['VENUE_EMPTY', 'VENUE_UNREADABLE'] as const;

/** True for a VENUE run that met an empty or unreadable venue site. */
export function isBreakage(run: Pick<IngestionRun, 'source' | 'issueCounts'> | undefined): boolean {
  if (!run || run.source !== 'VENUE') return false;
  return BREAKAGE_KINDS.some((kind) => (run.issueCounts[kind] ?? 0) > 0);
}

/** After a press, how long a run may stay QUEUED before the page says no scheduler seems to be running. */
export const WAITING_NOTE_AFTER_MS = 60_000;

/** How often a pressed run is polled until it ends. */
export const RUN_POLL_MS = 3_000;
