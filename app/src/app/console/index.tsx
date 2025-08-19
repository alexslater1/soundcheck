import { LazyPlaceholderPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

// Placeholder until the page is built (features/console/nav.ts says which).
export default function ConsoleIndex() {
  return (
    <PageSuspense>
      <LazyPlaceholderPage page="index" />
    </PageSuspense>
  );
}
