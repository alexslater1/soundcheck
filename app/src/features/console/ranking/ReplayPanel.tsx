import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { adminReplayRanking } from '@/api/generated/admin-ranking/admin-ranking';
import type { RankingConfigRef, RankingReplayResult } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { Chart } from '../charts/Chart';
import { useConsoleRange } from '../useConsoleRange';
import { metric, replayWindow } from './configModel';

type Side = { label: string; config: RankingConfigRef };

type Run = { from: string; to: string; explorationOnly: boolean; sides: Side[] };

/**
 * Replay evaluation: the active config and the draft re-score what was served in the console's date
 * range (at most its last 14 days), compared on AUC, log-loss, NDCG@10 and engagement by position.
 * Run on request, not on every slider move: it reads up to 200 000 served items.
 */
export function ReplayPanel({ active, draft }: { active: Side | null; draft: Side | null }) {
  const { colors } = useTheme();
  const [range] = useConsoleRange();
  const window = replayWindow(range);
  const [explorationOnly, setExplorationOnly] = useState(false);
  const [run, setRun] = useState<Run | null>(null);

  const sides = [active, draft].filter((s): s is Side => s !== null);
  const results = useQuery({
    queryKey: ['/api/v1/admin/ranking/replay', run],
    queryFn: ({ signal }) =>
      Promise.all(
        (run as Run).sides.map((side) =>
          adminReplayRanking(
            {
              config: side.config,
              from: (run as Run).from,
              to: (run as Run).to,
              explorationOnly: (run as Run).explorationOnly,
            },
            { signal },
          ),
        ),
      ),
    enabled: run !== null,
    staleTime: Infinity,
  });

  return (
    <View
      style={[styles.panel, { borderColor: colors.border, backgroundColor: colors.surface }]}
      testID="replay-panel"
    >
      <Text variant="title">Replay evaluation</Text>
      <View
        style={[styles.caveat, { backgroundColor: colors.accent2Soft }]}
        testID="replay-caveat"
        accessibilityRole="alert"
      >
        <Text variant="caption">
          Biased towards the config that served these items. The log only holds what the active
          config chose to show, in the order it showed it, so a draft is judged only on posts the
          active one picked, and it can never get credit for a post the active config left out.
          Exploration-only uses the slots filled at random: fairer, but far fewer rows.
        </Text>
      </View>
      <View style={styles.controls}>
        <Text variant="caption" muted testID="replay-window">
          {window.from.toISOString().slice(0, 16).replace('T', ' ')} →{' '}
          {window.to.toISOString().slice(0, 16).replace('T', ' ')} UTC
          {window.clamped ? ' (the last 14 days of the range: replay reads at most 14)' : ''}
        </Text>
        <Pressable
          role="checkbox"
          aria-checked={explorationOnly}
          onPress={() => setExplorationOnly((x) => !x)}
          testID="replay-exploration-only"
          style={[
            styles.toggle,
            { borderColor: explorationOnly ? colors.accent : colors.border },
            explorationOnly ? { backgroundColor: colors.accentSoft } : null,
          ]}
        >
          <Text variant="caption">{explorationOnly ? '☑' : '☐'} Exploration slots only</Text>
        </Pressable>
        <Button
          title="Run replay"
          size="small"
          variant="secondary"
          disabled={sides.length === 0}
          onPress={() =>
            setRun({
              from: window.from.toISOString(),
              to: window.to.toISOString(),
              explorationOnly,
              sides,
            })
          }
          testID="replay-run"
        />
      </View>
      {run === null ? (
        <Text muted>Choose a range in the top bar, then run the replay.</Text>
      ) : results.status === 'pending' ? (
        <View testID="replay-loading" style={styles.loading}>
          <Skeleton height={20} width="40%" />
          <Skeleton height={120} />
        </View>
      ) : results.status === 'error' ? (
        <StateMessage
          testID="replay-error"
          title="Replay failed"
          detail={
            results.error instanceof Error ? results.error.message : 'The server did not answer.'
          }
          onRetry={() => void results.refetch()}
        />
      ) : (
        <ReplayResults sides={run.sides} results={results.data} />
      )}
    </View>
  );
}

function ReplayResults({ sides, results }: { sides: Side[]; results: RankingReplayResult[] }) {
  const { colors } = useTheme();
  if (results.every((r) => r.rows === 0)) {
    return (
      <StateMessage
        testID="replay-empty"
        title="Nothing was served in this window"
        detail="Pick a range in which people scrolled the For You feed."
      />
    );
  }
  const rows: { label: string; value: (r: RankingReplayResult) => string }[] = [
    { label: 'AUC', value: (r) => metric(r.auc) },
    { label: 'Log-loss', value: (r) => metric(r.logLoss) },
    { label: 'NDCG@10', value: (r) => metric(r.ndcgAt10) },
    { label: 'Served items', value: (r) => String(r.rows) },
    {
      label: 'Sessions (with a positive)',
      value: (r) => `${r.sessions} (${r.sessionsWithPositive})`,
    },
    { label: 'Positives', value: (r) => String(r.positives) },
  ];
  const positions = Math.max(...results.map((r) => r.byPosition.length));
  return (
    <View style={styles.results} testID="replay-results">
      {results.some((r) => r.sampled) ? (
        <Text variant="caption" style={{ color: colors.warning }}>
          Over 200 000 served items: a fixed {(results[0]!.sampleFraction * 100).toFixed(1)} % of
          the sessions was replayed.
        </Text>
      ) : null}
      <View style={styles.table}>
        <View style={[styles.row, { borderBottomColor: colors.border }]}>
          <Text variant="label" muted style={styles.cell}>
            METRIC
          </Text>
          {sides.map((s) => (
            <Text key={s.label} variant="label" muted style={styles.cell}>
              {s.label.toUpperCase()}
            </Text>
          ))}
        </View>
        {rows.map((row) => (
          <View key={row.label} style={[styles.row, { borderBottomColor: colors.border }]}>
            <Text variant="caption" style={styles.cell}>
              {row.label}
            </Text>
            {results.map((r, i) => (
              <Text
                key={sides[i]!.label}
                variant="caption"
                style={styles.cell}
                testID={`replay-${row.label}-${i}`}
              >
                {row.value(r)}
              </Text>
            ))}
          </View>
        ))}
      </View>
      {positions > 0 ? (
        <Chart
          title="Positive rate by position in each config's order"
          testID="replay-by-position"
          spec={{
            kind: 'bar',
            categories: Array.from({ length: positions }, (_, i) => String(i + 1)),
            series: results.map((r, i) => ({
              key: `s${i}`,
              label: sides[i]!.label,
              values: Array.from({ length: positions }, (_, p) => r.byPosition[p]?.rate ?? 0),
            })),
            unit: 'rate',
          }}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  caveat: { borderRadius: radius.sm, padding: spacing.sm },
  controls: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, flexWrap: 'wrap' },
  toggle: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 4,
  },
  loading: { gap: spacing.sm },
  results: { gap: spacing.md },
  table: { maxWidth: 640 },
  row: { flexDirection: 'row', borderBottomWidth: StyleSheet.hairlineWidth, paddingVertical: 3 },
  cell: { flex: 1 },
});
