import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import {
  adminListReports,
  adminResolveReport,
  adminRestorePost,
  getAdminListReportsQueryKey,
} from '@/api/generated/admin-moderation/admin-moderation';
import type { ReportItem, ResolveRequestAction } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ConfirmDialog } from '../ConfirmDialog';
import {
  ACTION_LABELS,
  decisionChanges,
  kindLabel,
  problemMessage,
  RESTORE_CHANGES,
  type ModerationTab,
} from './moderationModel';
import { ReportCard } from './ReportCard';

const TABS: { id: ModerationTab; label: string }[] = [
  { id: 'OPEN', label: 'Open' },
  { id: 'RESOLVED', label: 'Resolved' },
];

const PAGE = 20;

/** A decision or a restore waiting for its confirmation. */
type Pending =
  | { kind: 'decide'; item: ReportItem; action: ResolveRequestAction }
  | { kind: 'restore'; item: ReportItem };

/**
 * The console's Moderation page. Open lists what fans reported, one card per post or
 * comment, the longest-waiting first; each decision (hide the post, delete the comment, dismiss) goes
 * through a confirmation that says what will change, and resolves every open report on it. Resolved is
 * the history, latest decision first, with Restore for a post that is still hidden.
 */
export default function ModerationPage() {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [tab, setTab] = useState<ModerationTab>('OPEN');
  const [pending, setPending] = useState<Pending | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const params = { status: tab, limit: PAGE };
  const query = useInfiniteQuery({
    queryKey: [...getAdminListReportsQueryKey(params), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      adminListReports({ ...params, ...(pageParam ? { cursor: pageParam } : {}) }, { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];

  const refresh = () => client.invalidateQueries({ queryKey: getAdminListReportsQueryKey() });
  const close = () => {
    setPending(null);
    setProblem(null);
  };
  const confirm = async () => {
    if (!pending) return;
    setBusy(true);
    setProblem(null);
    try {
      if (pending.kind === 'decide') {
        await adminResolveReport(pending.item.reportId, { action: pending.action });
      } else {
        await adminRestorePost(pending.item.id);
      }
      close();
      await refresh();
    } catch (error) {
      setProblem(problemMessage(error));
      // Someone else's decision changed the list: show it as it is now.
      void refresh();
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.page}>
      <Text variant="display">Moderation</Text>
      <View style={styles.tabs} role="tablist">
        {TABS.map((t) => (
          <Pressable
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            onPress={() => setTab(t.id)}
            testID={`moderation-tab-${t.id.toLowerCase()}`}
            style={[
              styles.tab,
              { borderColor: colors.border },
              tab === t.id
                ? { backgroundColor: colors.accentSoft, borderColor: colors.accent }
                : null,
            ]}
          >
            <Text variant="caption">{t.label}</Text>
          </Pressable>
        ))}
      </View>

      {query.status === 'pending' ? (
        <View style={styles.list} testID="moderation-loading">
          <Skeleton height={180} />
          <Skeleton height={180} />
        </View>
      ) : query.status === 'error' ? (
        <StateMessage
          testID="moderation-error"
          title="Couldn't load the reports"
          detail="The server didn't answer."
          onRetry={() => void query.refetch()}
        />
      ) : items.length === 0 ? (
        <StateMessage
          testID="moderation-empty"
          title={tab === 'OPEN' ? 'Nothing to review' : 'Nothing resolved yet'}
          detail={
            tab === 'OPEN'
              ? 'Reports from fans show up here, one card per post or comment.'
              : 'Decisions on reports show up here, the latest first.'
          }
        />
      ) : (
        <View style={styles.list} testID="moderation-list">
          {items.map((item) => (
            <ReportCard
              key={`${item.kind}-${item.id}`}
              item={item}
              onDecide={(i, action) => setPending({ kind: 'decide', item: i, action })}
              onRestore={(i) => setPending({ kind: 'restore', item: i })}
            />
          ))}
          {query.hasNextPage ? (
            <Button
              title={query.isFetchingNextPage ? 'Loading…' : 'Load more'}
              variant="secondary"
              disabled={query.isFetchingNextPage}
              onPress={() => void query.fetchNextPage()}
              testID="moderation-more"
            />
          ) : null}
        </View>
      )}

      <ConfirmDialog
        visible={pending !== null}
        title={
          pending?.kind === 'restore'
            ? 'Restore this post?'
            : pending
              ? `${ACTION_LABELS[pending.action]}: ${kindLabel(pending.item.kind).toLowerCase()}?`
              : ''
        }
        changes={
          pending?.kind === 'restore'
            ? RESTORE_CHANGES
            : pending
              ? decisionChanges(pending.item, pending.action)
              : []
        }
        confirmLabel={
          pending?.kind === 'restore' ? 'Restore' : pending ? ACTION_LABELS[pending.action] : ''
        }
        destructive={pending?.kind === 'decide' && pending.action !== 'DISMISS'}
        busy={busy}
        error={problem}
        onConfirm={() => void confirm()}
        onCancel={close}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  tabs: { flexDirection: 'row', gap: spacing.xs },
  tab: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 4,
  },
  list: { gap: spacing.md, maxWidth: 960 },
});
