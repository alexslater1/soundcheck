import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import {
  adminListFanAddedGigs,
  getAdminListFanAddedGigsQueryKey,
  useAdminDeleteGig,
  useAdminReviewFanAddedGig,
} from '@/api/generated/admin-catalog/admin-catalog';
import type { FanAddedGig } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ConfirmDialog } from '../ConfirmDialog';
import { DataTable, type Column } from '../DataTable';
import { problemLines } from '../ranking/configModel';
import { billLine, gigLine } from './mergeModel';
import type { CatalogTabProps } from './tabTypes';
import { useHandles, who } from './useHandles';

type Queue = 'open' | 'reviewed';

/**
 * The Fan gigs tab: gigs fans added, newest first, with who added them, their
 * marks and posts, and the gigs they may duplicate. Review takes one out of the queue; "Merge into…"
 * opens the Merge tab on that pair; Delete is for a gig nothing hangs off (no posts, no comments).
 */
export function FanGigsTab({ openMerge }: CatalogTabProps) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [queue, setQueue] = useState<Queue>('open');
  const [deleting, setDeleting] = useState<FanAddedGig | null>(null);
  const params = { reviewed: queue === 'reviewed' };
  const query = useInfiniteQuery({
    queryKey: [...getAdminListFanAddedGigsQueryKey(params), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      adminListFanAddedGigs({ ...params, ...(pageParam ? { cursor: pageParam } : {}) }, { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const rows = query.data?.pages.flatMap((p) => p.items);
  const handles = useHandles((rows ?? []).flatMap((r) => [r.addedBy, r.reviewedBy]));
  const refresh = () => client.invalidateQueries({ queryKey: getAdminListFanAddedGigsQueryKey() });
  const review = useAdminReviewFanAddedGig({ mutation: { onSuccess: refresh } });
  const remove = useAdminDeleteGig({
    mutation: {
      onSuccess: () => {
        setDeleting(null);
        return refresh();
      },
    },
  });

  const columns: Column<FanAddedGig>[] = [
    {
      key: 'gig',
      title: 'Gig',
      flex: 3,
      sortValue: (r) => r.gig.startsAt,
      render: (r) => (
        <View>
          <Link href={{ pathname: '/gig/[id]', params: { id: r.gig.id } }}>
            <Text style={{ color: colors.accent }}>{gigLine(r.gig)}</Text>
          </Link>
          <Text variant="caption" muted>
            {billLine(r.gig)}
          </Text>
        </View>
      ),
    },
    {
      key: 'added',
      title: 'Added',
      flex: 1.5,
      sortValue: (r) => r.addedAt,
      render: (r) => (
        <View>
          <Text variant="caption">{who(handles, r.addedBy)}</Text>
          <Text variant="caption" muted>
            {r.addedAt.slice(0, 10)}
          </Text>
          {r.reviewedAt ? (
            <Text variant="caption" muted>
              reviewed by {who(handles, r.reviewedBy)}
            </Text>
          ) : null}
        </View>
      ),
    },
    {
      key: 'attendance',
      title: 'Marks',
      align: 'right',
      sortValue: (r) => r.attendance,
      render: (r) => <Text>{r.attendance}</Text>,
    },
    {
      key: 'posts',
      title: 'Posts',
      align: 'right',
      sortValue: (r) => r.posts,
      render: (r) => <Text>{r.posts}</Text>,
    },
    {
      key: 'duplicates',
      title: 'Likely duplicates',
      flex: 3,
      sortValue: (r) => r.likelyDuplicates.length,
      render: (r) =>
        r.likelyDuplicates.length === 0 ? (
          <Text variant="caption" muted>
            None found
          </Text>
        ) : (
          <View style={styles.dups}>
            {r.likelyDuplicates.map((d) => (
              <View key={d.id} style={styles.dup}>
                <Text variant="caption" style={styles.dupText}>
                  {gigLine(d)}
                  {d.userAdded ? ' · fan-added' : ''}
                </Text>
                <Pressable
                  accessibilityRole="button"
                  onPress={() => openMerge(r.gig, d)}
                  testID={`fan-gig-merge-${r.gig.id}-${d.id}`}
                >
                  <Text variant="caption" style={{ color: colors.accent }}>
                    Merge into…
                  </Text>
                </Pressable>
              </View>
            ))}
          </View>
        ),
    },
    {
      key: 'actions',
      title: '',
      flex: 1.5,
      render: (r) => (
        <View style={styles.actions}>
          {r.reviewedAt ? null : (
            <Button
              title="Reviewed"
              size="small"
              variant="outline"
              disabled={review.isPending}
              onPress={() => review.mutate({ id: r.gig.id })}
              testID={`fan-gig-review-${r.gig.id}`}
            />
          )}
          <Button
            title="Delete…"
            size="small"
            variant="secondary"
            onPress={() => {
              remove.reset();
              setDeleting(r);
            }}
            testID={`fan-gig-delete-${r.gig.id}`}
          />
        </View>
      ),
    },
  ];

  return (
    <View style={styles.page}>
      <View style={styles.filters} role="radiogroup" accessibilityLabel="Which fan gigs">
        {(
          [
            ['open', 'To review'],
            ['reviewed', 'Reviewed'],
          ] as const
        ).map(([id, label]) => (
          <Pressable
            key={id}
            role="radio"
            aria-checked={queue === id}
            onPress={() => setQueue(id)}
            testID={`fan-gigs-${id}`}
            style={[
              styles.filter,
              { borderColor: colors.border },
              queue === id
                ? { backgroundColor: colors.accentSoft, borderColor: colors.accent }
                : null,
            ]}
          >
            <Text variant="caption">{label}</Text>
          </Pressable>
        ))}
      </View>
      {review.isError ? (
        <Text style={{ color: colors.danger }}>{problemLines(review.error).join(' · ')}</Text>
      ) : null}
      <DataTable
        testID="fan-gigs-table"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.gig.id}
        status={query.status}
        onRetry={() => void query.refetch()}
        emptyTitle={queue === 'open' ? 'No fan-added gigs to review' : 'Nothing reviewed yet'}
        emptyDetail={
          queue === 'open'
            ? 'Gigs fans add (Add a gig, when no source lists the show) appear here.'
            : undefined
        }
        hasMore={query.hasNextPage}
        loadingMore={query.isFetchingNextPage}
        onLoadMore={() => void query.fetchNextPage()}
      />
      {deleting ? (
        <ConfirmDialog
          visible
          title="Delete this gig?"
          changes={[
            `Delete ${gigLine(deleting.gig)}`,
            `Remove ${deleting.attendance} fan mark${deleting.attendance === 1 ? '' : 's'}, its bill and listings`,
            'Refused if it has posts or comments: merge it instead',
          ]}
          confirmLabel="Delete"
          destructive
          busy={remove.isPending}
          error={remove.isError ? problemLines(remove.error).join(' · ') : null}
          onConfirm={() => remove.mutate({ id: deleting.gig.id })}
          onCancel={() => setDeleting(null)}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  filters: { flexDirection: 'row', gap: spacing.xs },
  filter: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 4,
  },
  dups: { gap: spacing.xs },
  dup: { flexDirection: 'row', gap: spacing.sm, alignItems: 'center', flexWrap: 'wrap' },
  dupText: { flexShrink: 1 },
  actions: { flexDirection: 'row', gap: spacing.xs, flexWrap: 'wrap' },
});
