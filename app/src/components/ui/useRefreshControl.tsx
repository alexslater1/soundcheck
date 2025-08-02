import { useCallback, useState } from 'react';
import { RefreshControl } from 'react-native';

import { MIN_REFRESH_MS } from '@/features/feed/usePullToRefresh';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Pull to refresh for an ordinary scrolling screen: pass the result as a ScrollView's or
 * FlatList's `refreshControl`. `refresh` fetches what the screen shows again; the spinner stays at least
 * MIN_REFRESH_MS so a fast answer doesn't flicker. The paged feeds use `usePullToRefresh` instead,
 * because a snapping pager can't host a RefreshControl. On the web RefreshControl draws
 * nothing, so these screens refresh there by reloading, as before.
 */
export function useRefreshControl(refresh: () => Promise<unknown>) {
  const { colors } = useTheme();
  const [refreshing, setRefreshing] = useState(false);
  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await Promise.all([
        refresh().catch(() => undefined),
        new Promise((resolve) => setTimeout(resolve, MIN_REFRESH_MS)),
      ]);
    } finally {
      setRefreshing(false);
    }
  }, [refresh]);
  return (
    <RefreshControl
      refreshing={refreshing}
      onRefresh={() => void onRefresh()}
      tintColor={colors.accent}
      colors={[colors.accent]}
      progressBackgroundColor={colors.surfaceRaised}
    />
  );
}
