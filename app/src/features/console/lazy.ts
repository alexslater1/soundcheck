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
export const LazyRankingPage = lazy(() => import('./ranking/RankingPage'));
export const LazyInspectorPage = lazy(() => import('./ranking/InspectorPage'));
export const LazyPipelinePage = lazy(() => import('./pipeline/PipelinePage'));
export const LazyOverviewPage = lazy(() => import('./overview/OverviewPage'));
export const LazyIngestionPage = lazy(() => import('./ingestion/IngestionPage'));
export const LazyModerationPage = lazy(() => import('./moderation/ModerationPage'));
