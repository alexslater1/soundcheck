import { LazyPlaceholderPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

// Placeholder until the page is built (features/console/nav.ts says which).
export default function ConsoleInspector() {
  return (
    <PageSuspense>
      <LazyPlaceholderPage page="inspector" />
    </PageSuspense>
  );
}
