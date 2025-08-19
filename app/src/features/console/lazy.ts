import { lazy } from 'react';

/**
 * Every lazily loaded piece of the console, in one place. Each `import()` becomes its
 * own chunk in the web export, fetched only when `/console` is opened, so fans never download the
 * console or Recharts. (Tests mock this module: Jest here can't run `import()`.)
 */
export const LazyConsoleShell = lazy(() =>
  import('./ConsoleShell').then((m) => ({ default: m.ConsoleShell })),
);
export const LazyPlaceholderPage = lazy(() => import('./PlaceholderPage'));
export const LazyUsersPage = lazy(() => import('./UsersPage'));
