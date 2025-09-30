import type { GigSummary, VenueMergeResult } from '@/api/generated/model';

/** A pair of gigs the Fan gigs tab hands to the Merge tab ("Merge into…"); `nonce` remounts it. */
export type MergePair = { from: GigSummary; into: GigSummary; nonce: number };

/** What every Catalog tab is given; a tab uses what it needs. */
export type CatalogTabProps = {
  /** Switches to the Merge tab with this duplicate and the gig to keep filled in. */
  openMerge: (from: GigSummary, into: GigSummary) => void;
  /** The pair last handed over, if any. */
  pair: MergePair | null;
  /** Switches tab (the Merge tab's way back to a venue merge's same-night list). */
  openTab: (key: string) => void;
  /**
   * The last venue merge and its same-night list. Kept by the page, not the Venues tab, so the list
   * survives "Merge these…" switching to the Merge tab and back.
   */
  venueMerge: VenueMergeDone | null;
  setVenueMerge: (done: VenueMergeDone | null) => void;
  /** Gigs merged away on this visit, so the same-night list can tick the pairs already done. */
  mergedGigIds: ReadonlySet<string>;
  onGigMerged: (fromId: string) => void;
};

/** A finished venue merge: what moved, and the gigs that now share a night at the venue that stays. */
export type VenueMergeDone = { result: VenueMergeResult; name: string };
