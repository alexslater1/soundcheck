import { LazyPlaceholderPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

// Placeholder until the page is built (features/console/nav.ts says which).
export default function ConsoleRanking() {
  return (
    <PageSuspense>
      <LazyPlaceholderPage page="ranking" />
    </PageSuspense>
  );
}
