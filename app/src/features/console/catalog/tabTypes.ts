import type { GigSummary } from '@/api/generated/model';

/** A pair of gigs the Fan gigs tab hands to the Merge tab ("Merge into…"); `nonce` remounts it. */
export type MergePair = { from: GigSummary; into: GigSummary; nonce: number };

/** What every Catalog tab is given; a tab uses what it needs. */
export type CatalogTabProps = {
  /** Switches to the Merge tab with this duplicate and the gig to keep filled in. */
  openMerge: (from: GigSummary, into: GigSummary) => void;
  /** The pair last handed over, if any. */
  pair: MergePair | null;
};
