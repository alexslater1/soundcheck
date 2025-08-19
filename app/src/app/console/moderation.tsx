import { LazyPlaceholderPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

// Placeholder until the page is built (features/console/nav.ts says which).
export default function ConsoleModeration() {
  return (
    <PageSuspense>
      <LazyPlaceholderPage page="moderation" />
    </PageSuspense>
  );
}
