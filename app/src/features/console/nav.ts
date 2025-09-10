import type { Href } from 'expo-router';

export type ConsoleNavItem = {
  /** The route file under `src/app/console/` ('index' is the overview). */
  key: string;
  href: Href;
  label: string;
  /** One line under the label in the nav and on the page's placeholder. */
  blurb: string;
  /** False while the page is still a placeholder ("Not built yet"). */
  built: boolean;
};

/** Every console page, in nav order. */
export const CONSOLE_NAV: readonly ConsoleNavItem[] = [
  {
    key: 'index',
    href: '/console',
    label: 'Overview',
    blurb: 'Activity, uploads and feed engagement at a glance',
    built: true,
  },
  {
    key: 'ranking',
    href: '/console/ranking',
    label: 'Ranking playground',
    blurb: 'Tune weights, preview a feed, activate a config',
    built: true,
  },
  {
    key: 'inspector',
    href: '/console/inspector',
    label: 'Feed inspector',
    blurb: 'What one user would see now, and what they were shown',
    built: true,
  },
  {
    key: 'pipeline',
    href: '/console/pipeline',
    label: 'Media pipeline',
    blurb: 'Transcode queue, stage times, failures and retries',
    built: true,
  },
  {
    key: 'ingestion',
    href: '/console/ingestion',
    label: 'Ingestion',
    blurb: 'Gig source runs, issues and "Run now"',
    built: true,
  },
  {
    key: 'moderation',
    href: '/console/moderation',
    label: 'Moderation',
    blurb: 'Reports, hidden posts and restores',
    built: true,
  },
  {
    key: 'catalog',
    href: '/console/catalog',
    label: 'Catalog',
    blurb: 'Merge duplicate gigs, review fan edits and matches',
    built: true,
  },
  {
    key: 'users',
    href: '/console/users',
    label: 'Users',
    blurb: 'Find an account; synthetic personas are tagged',
    built: true,
  },
];

export function navItem(key: string): ConsoleNavItem {
  const item = CONSOLE_NAV.find((i) => i.key === key);
  if (!item) throw new Error(`No console page '${key}'`);
  return item;
}
