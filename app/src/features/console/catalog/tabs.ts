import { createElement, type ComponentType } from 'react';

import { FanGigsTab } from './FanGigsTab';
import { MergeTab } from './MergeTab';
import { TabPlaceholder } from './TabPlaceholder';
import type { CatalogTabProps } from './tabTypes';

export type CatalogTab = {
  key: string;
  label: string;
  Component: ComponentType<CatalogTabProps>;
};

/** A tab still to be built: its placeholder says so. */
function later(blurb: string): ComponentType<CatalogTabProps> {
  const Placeholder = () => createElement(TabPlaceholder, { blurb });
  Placeholder.displayName = 'TabPlaceholder';
  return Placeholder;
}

/** The tab the page opens on. */
export const FAN_GIGS: CatalogTab = { key: 'fan-gigs', label: 'Fan gigs', Component: FanGigsTab };

/**
 * The Catalog page's tabs, in order. Fan gigs and Merge are built; later work replaces
 * each placeholder with its own component, one line each.
 */
export const CATALOG_TABS: readonly CatalogTab[] = [
  FAN_GIGS,
  { key: 'merge', label: 'Merge', Component: MergeTab },
  { key: 'matches', label: 'Matches', Component: later('Listings that almost matched a gig') },
  { key: 'venues', label: 'Venues', Component: later('Venue aliases and locations') },
  { key: 'edits', label: 'Edit history', Component: later("Fans' edits of gigs, and undo") },
];
