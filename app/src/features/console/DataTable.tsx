import { useMemo, useState, type ReactNode } from 'react';
import { Platform, Pressable, StyleSheet, View, type ViewStyle } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

export type SortValue = string | number | boolean | null | undefined;

export type Column<T> = {
  key: string;
  title: string;
  render: (row: T) => ReactNode;
  /** Present when the column can be sorted by clicking its header. */
  sortValue?: (row: T) => SortValue;
  /** Relative width (flex); 1 by default. */
  flex?: number;
  align?: 'left' | 'right';
};

export type SortState = { key: string; direction: 'asc' | 'desc' } | null;

/**
 * Sorts the loaded rows by one column (a stable sort: ties keep the server's order). Nulls go last in
 * both directions. Sorting is client-side: it orders what has been loaded, not the whole table.
 */
export function sortRows<T>(
  rows: readonly T[],
  columns: readonly Column<T>[],
  sort: SortState,
): T[] {
  const column = sort ? columns.find((c) => c.key === sort.key) : undefined;
  if (!sort || !column?.sortValue) return [...rows];
  const value = column.sortValue;
  const sign = sort.direction === 'asc' ? 1 : -1;
  return rows
    .map((row, index) => ({ row, index, v: value(row) }))
    .sort((a, b) => {
      const aNull = a.v === null || a.v === undefined;
      const bNull = b.v === null || b.v === undefined;
      if (aNull || bNull) return aNull === bNull ? a.index - b.index : aNull ? 1 : -1;
      const av = typeof a.v === 'boolean' ? Number(a.v) : a.v;
      const bv = typeof b.v === 'boolean' ? Number(b.v) : b.v;
      const order =
        typeof av === 'string' && typeof bv === 'string'
          ? av.localeCompare(bv)
          : Number(av) - Number(bv);
      return order === 0 ? a.index - b.index : sign * order;
    })
    .map((x) => x.row);
}

/** Clicking a header: ascending, then descending, then the server's order again. */
export function nextSort(current: SortState, key: string): SortState {
  if (!current || current.key !== key) return { key, direction: 'asc' };
  return current.direction === 'asc' ? { key, direction: 'desc' } : null;
}

// react-native-web passes `position: sticky` through; React Native's types don't list it.
const STICKY: ViewStyle =
  Platform.OS === 'web' ? ({ position: 'sticky', top: 0, zIndex: 1 } as unknown as ViewStyle) : {};

/**
 * The console's one table: sortable headers, a sticky header row, a cursor "Load more",
 * and the loading skeleton, empty and error states built in, so every page gets them the same way.
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  status,
  onRetry,
  emptyTitle,
  emptyDetail,
  hasMore = false,
  loadingMore = false,
  onLoadMore,
  testID,
}: {
  columns: readonly Column<T>[];
  rows: readonly T[] | undefined;
  rowKey: (row: T) => string;
  status: 'pending' | 'error' | 'success';
  onRetry?: () => void;
  emptyTitle: string;
  emptyDetail?: string;
  hasMore?: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
  testID?: string;
}) {
  const { colors } = useTheme();
  const [sort, setSort] = useState<SortState>(null);
  const sorted = useMemo(() => sortRows(rows ?? [], columns, sort), [rows, columns, sort]);

  if (status === 'error') {
    return (
      <StateMessage
        testID={testID ? `${testID}-error` : undefined}
        title="Couldn't load this table"
        detail="The server didn't answer. Your filters are kept."
        onRetry={onRetry}
      />
    );
  }

  return (
    <View testID={testID} accessibilityRole="list">
      <View
        style={[
          styles.row,
          styles.head,
          STICKY,
          { backgroundColor: colors.background, borderBottomColor: colors.border },
        ]}
      >
        {columns.map((c) => {
          const active = sort?.key === c.key;
          const arrow = active ? (sort?.direction === 'asc' ? ' ▲' : ' ▼') : '';
          const title = (
            <Text variant="label" muted={!active} style={c.align === 'right' ? styles.right : null}>
              {c.title.toUpperCase()}
              {arrow}
            </Text>
          );
          return (
            <View key={c.key} style={[styles.cell, { flex: c.flex ?? 1 }]}>
              {c.sortValue ? (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`Sort by ${c.title}`}
                  onPress={() => setSort((s) => nextSort(s, c.key))}
                  testID={testID ? `${testID}-sort-${c.key}` : undefined}
                >
                  {title}
                </Pressable>
              ) : (
                title
              )}
            </View>
          );
        })}
      </View>
      {status === 'pending' ? (
        <View style={styles.skeletons} testID={testID ? `${testID}-loading` : undefined}>
          {[0, 1, 2, 3, 4].map((i) => (
            <Skeleton key={i} height={28} />
          ))}
        </View>
      ) : sorted.length === 0 ? (
        <StateMessage
          testID={testID ? `${testID}-empty` : undefined}
          title={emptyTitle}
          detail={emptyDetail}
        />
      ) : (
        sorted.map((row) => (
          <View
            key={rowKey(row)}
            testID={testID ? `${testID}-row` : undefined}
            style={[styles.row, { borderBottomColor: colors.border }]}
          >
            {columns.map((c) => (
              <View
                key={c.key}
                style={[
                  styles.cell,
                  { flex: c.flex ?? 1 },
                  c.align === 'right' ? styles.rightCell : null,
                ]}
              >
                {c.render(row)}
              </View>
            ))}
          </View>
        ))
      )}
      {status === 'success' && hasMore && onLoadMore ? (
        <View style={styles.more}>
          <Button
            title={loadingMore ? 'Loading…' : 'Load more'}
            variant="secondary"
            size="small"
            disabled={loadingMore}
            onPress={onLoadMore}
            testID={testID ? `${testID}-more` : undefined}
          />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
    paddingVertical: spacing.sm,
    gap: spacing.sm,
  },
  head: { paddingVertical: spacing.sm },
  cell: { minWidth: 0 },
  right: { textAlign: 'right' },
  rightCell: { alignItems: 'flex-end' },
  skeletons: { gap: spacing.sm, paddingVertical: spacing.sm },
  more: { paddingVertical: spacing.md, alignItems: 'flex-start' },
});
