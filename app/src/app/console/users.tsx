import { LazyUsersPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

export default function ConsoleUsers() {
  return (
    <PageSuspense>
      <LazyUsersPage />
    </PageSuspense>
  );
}
