import type { ComponentType } from 'react';

import { EditHistoryTab } from './EditHistoryTab';
import { FanGigsTab } from './FanGigsTab';
import { MatchesTab } from './MatchesTab';
import { MergeTab } from './MergeTab';
import type { CatalogTabProps } from './tabTypes';
import { VenuesTab } from './VenuesTab';

export type CatalogTab = {
  key: string;
  label: string;
  Component: ComponentType<CatalogTabProps>;
};

/** The tab the page opens on. */
export const FAN_GIGS: CatalogTab = { key: 'fan-gigs', label: 'Fan gigs', Component: FanGigsTab };

/**
 * The Catalog page's tabs, in order: Fan gigs, Merge, Matches (ingestion's "same gig?" reviews),
 * Venues (merge, locations) and Edit history (undo).
 */
export const CATALOG_TABS: readonly CatalogTab[] = [
  FAN_GIGS,
  { key: 'merge', label: 'Merge', Component: MergeTab },
  { key: 'matches', label: 'Matches', Component: MatchesTab },
  { key: 'venues', label: 'Venues', Component: VenuesTab },
  { key: 'edits', label: 'Edit history', Component: EditHistoryTab },
];
