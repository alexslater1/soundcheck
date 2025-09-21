import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import {
  adminListMatchReviews,
  getAdminListMatchReviewsQueryKey,
  useAdminDecideMatchReview,
} from '@/api/generated/admin-ingestion/admin-ingestion';
import type { GigSummary, MatchReview, MatchReviewStatus } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ConfirmDialog } from '../ConfirmDialog';
import { DataTable, type Column } from '../DataTable';
import { problemLines } from '../ranking/configModel';
import { billLine, gigLine } from './mergeModel';

const QUEUES: readonly [MatchReviewStatus, string][] = [
  ['OPEN', 'To decide'],
  ['MERGED', 'Merged'],
  ['KEPT_APART', 'Kept apart'],
];

/** 0.83 → "83 %". */
export function percent(similarity: number): string {
  return `${Math.round(similarity * 100)} %`;
}

/**
 * The Matches tab: listings the resolver almost matched to a gig it already had, and
 * kept apart. Merge runs the gig merge of the new listing's gig into the existing one; Keep apart
 * only answers the question. The resolver never asks about a linked listing again, either way.
 */
export function MatchesTab() {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [status, setStatus] = useState<MatchReviewStatus>('OPEN');
  const [merging, setMerging] = useState<MatchReview | null>(null);
  const params = { status };
  const query = useInfiniteQuery({
    queryKey: [...getAdminListMatchReviewsQueryKey(params), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      adminListMatchReviews({ ...params, ...(pageParam ? { cursor: pageParam } : {}) }, { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const rows = query.data?.pages.flatMap((p) => p.items);
  const decide = useAdminDecideMatchReview({
    mutation: {
      onSuccess: () => {
        setMerging(null);
        return client.invalidateQueries({ queryKey: getAdminListMatchReviewsQueryKey() });
      },
    },
  });

  const gigCell = (g: GigSummary) => (
    <View>
      <Link href={{ pathname: '/gig/[id]', params: { id: g.id } }}>
        <Text style={{ color: colors.accent }}>{gigLine(g)}</Text>
      </Link>
      <Text variant="caption" muted>
        {billLine(g)}
        {g.userAdded ? ' · added by a fan' : ''}
      </Text>
    </View>
  );

  const columns: Column<MatchReview>[] = [
    {
      key: 'listing',
      title: 'New listing (its gig)',
      flex: 3,
      sortValue: (r) => r.gig.startsAt,
      render: (r) => (
        <View>
          {gigCell(r.gig)}
          <Text variant="caption" muted>
            {r.source} {r.externalId}
          </Text>
        </View>
      ),
    },
    {
      key: 'candidate',
      title: 'Almost matched',
      flex: 3,
      render: (r) => gigCell(r.candidate),
    },
    {
      key: 'similarity',
      title: 'Why it asked',
      flex: 2,
      sortValue: (r) => r.titleSimilarity,
      render: (r) => (
        <View>
          <Text variant="caption">
            Titles {percent(r.titleSimilarity)} · acts {percent(r.actSimilarity)}
          </Text>
          <Text variant="caption" muted>
            {r.reason}
          </Text>
        </View>
      ),
    },
    {
      key: 'when',
      title: status === 'OPEN' ? 'Asked' : 'Decided',
      sortValue: (r) => (status === 'OPEN' ? r.createdAt : (r.decidedAt ?? null)),
      render: (r) => (
        <Text variant="caption" muted>
          {(status === 'OPEN' ? r.createdAt : (r.decidedAt ?? r.createdAt)).slice(0, 10)}
        </Text>
      ),
    },
    ...(status === 'OPEN'
      ? [
          {
            key: 'actions',
            title: '',
            flex: 1.5,
            render: (r: MatchReview) => (
              <View style={styles.actions}>
                <Button
                  title="Merge…"
                  size="small"
                  variant="danger"
                  onPress={() => {
                    decide.reset();
                    setMerging(r);
                  }}
                  testID={`match-merge-${r.id}`}
                />
                <Button
                  title="Keep apart"
                  size="small"
                  variant="outline"
                  disabled={decide.isPending}
                  onPress={() => decide.mutate({ id: r.id, data: { decision: 'KEEP_APART' } })}
                  testID={`match-keep-${r.id}`}
                />
              </View>
            ),
          },
        ]
      : []),
  ];

  return (
    <View style={styles.page} testID="catalog-matches">
      <Text muted>
        When a listing is close to a gig already in the catalog but not convincingly the same show,
        the resolver keeps them apart and asks here. Merge makes them one gig; keep apart leaves
        two.
      </Text>
      <View style={styles.filters} role="radiogroup" accessibilityLabel="Which match reviews">
        {QUEUES.map(([id, label]) => (
          <Pressable
            key={id}
            role="radio"
            aria-checked={status === id}
            onPress={() => setStatus(id)}
            testID={`matches-${id}`}
            style={[
              styles.filter,
              { borderColor: colors.border },
              status === id
                ? { backgroundColor: colors.accentSoft, borderColor: colors.accent }
                : null,
            ]}
          >
            <Text variant="caption">{label}</Text>
          </Pressable>
        ))}
      </View>
      {decide.isError && !merging ? (
        <Text style={{ color: colors.danger }}>{problemLines(decide.error).join(' · ')}</Text>
      ) : null}
      <DataTable
        testID="matches-table"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        status={query.status}
        onRetry={() => void query.refetch()}
        emptyTitle={status === 'OPEN' ? 'No listings to decide' : 'None yet'}
        emptyDetail={
          status === 'OPEN'
            ? 'Near-misses from ingestion runs (same venue and night, similar names) appear here.'
            : undefined
        }
        hasMore={query.hasNextPage}
        loadingMore={query.isFetchingNextPage}
        onLoadMore={() => void query.fetchNextPage()}
      />
      {merging ? (
        <ConfirmDialog
          visible
          title="Merge these gigs?"
          changes={[
            `Move everything on ${gigLine(merging.gig)} (marks, posts, listings…) to ${gigLine(merging.candidate)}`,
            `Delete ${gigLine(merging.gig)}; its old link opens the gig that stays`,
            'Refused, and left to decide, if a fan has a post at both',
          ]}
          confirmLabel="Merge"
          destructive
          busy={decide.isPending}
          error={decide.isError ? problemLines(decide.error).join(' · ') : null}
          onConfirm={() => decide.mutate({ id: merging.id, data: { decision: 'MERGE' } })}
          onCancel={() => setMerging(null)}
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
  actions: { flexDirection: 'row', gap: spacing.xs, flexWrap: 'wrap' },
});
