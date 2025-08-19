import { LazyPlaceholderPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

// Placeholder until the page is built (features/console/nav.ts says which).
export default function ConsoleIngestion() {
  return (
    <PageSuspense>
      <LazyPlaceholderPage page="ingestion" />
    </PageSuspense>
  );
}
