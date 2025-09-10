import { LazyCatalogPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

// The console's catalog: fan-added gigs and gig merges.
export default function ConsoleCatalog() {
  return (
    <PageSuspense>
      <LazyCatalogPage />
    </PageSuspense>
  );
}
