import { useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  adminActivateRankingConfig,
  adminCreateRankingConfig,
  adminUpdateRankingConfig,
  getAdminListRankingConfigsQueryKey,
  useAdminListRankingConfigs,
} from '@/api/generated/admin-ranking/admin-ranking';
import type { RankingConfigView, RankingPreviewItem } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ConfirmDialog } from '../ConfirmDialog';
import {
  configDiff,
  editable,
  groupConfigs,
  labSummary,
  LOCKED_REASON,
  LOCKED_WEIGHT,
  metric,
  PARAM_SLIDERS,
  previousConfig,
  problemLines,
  WEIGHT_RANGE,
  weightKeys,
  type EditableConfig,
} from './configModel';
import { PreviewColumn } from './PreviewColumn';
import { ReplayPanel } from './ReplayPanel';
import { SliderRow } from './SliderRow';
import { usePreview } from './usePreview';
import { UserPicker, type PickedUser } from './UserPicker';
import { WaterfallDrawer } from './WaterfallDrawer';

type Pending = { kind: 'activate' | 'restore'; target: RankingConfigView } | null;

function label(config: RankingConfigView): string {
  return `v${config.version} ${config.status.toLowerCase()}`;
}

/**
 * The ranking playground. Left: pick a config and move its weights and params.
 * Centre: one user's feed under the active config and under the edited one, side by side, with the
 * rank changes, overlap@10 and Kendall's τ; a row opens its score's waterfall. Below: replay of both
 * over what was served. Save as draft, Activate (a confirm listing every change) and Re-activate
 * previous act on the configs; nothing else here writes.
 */
export default function RankingPage() {
  const { colors } = useTheme();
  const router = useRouter();
  const queryClient = useQueryClient();
  const params = useLocalSearchParams<{ user?: string; handle?: string; config?: string }>();
  const configs = useAdminListRankingConfigs();

  const list = useMemo(() => configs.data ?? [], [configs.data]);
  const groups = groupConfigs(list);
  const active = groups.active;
  const selected = list.find((c) => c.id === params.config) ?? active;
  const previous = previousConfig(list);

  // The sliders' values: the selected config's own, until a slider moves. Edits belong to the config
  // they were made on, so selecting another config (or saving a new draft) starts from what it stores.
  const [edits, setEdits] = useState<{ id: string; config: EditableConfig } | null>(null);
  const editor = selected ? (edits?.id === selected.id ? edits.config : editable(selected)) : null;
  const edit = (change: (current: EditableConfig) => EditableConfig) => {
    if (selected && editor) setEdits({ id: selected.id, config: change(editor) });
  };

  const [picked, setPicked] = useState<PickedUser | null>(
    params.user ? { id: params.user, handle: params.handle ?? params.user.slice(0, 8) } : null,
  );
  const [exploration, setExploration] = useState(false);
  const [focus, setFocus] = useState<{ side: 'a' | 'b'; postId: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [pending, setPending] = useState<Pending>(null);

  const changes = selected && editor ? configDiff(editable(selected), editor) : [];
  const dirty = changes.length > 0;

  const request =
    picked && active && editor
      ? {
          userId: picked.id,
          configA: { id: active.id },
          configB: { weights: editor.weights, params: editor.params },
          limit: 20,
          exploration,
        }
      : null;
  const preview = usePreview(request);

  const select = (config: RankingConfigView) => {
    setErrors([]);
    router.setParams({ config: config.id });
  };
  const pickUser = (user: PickedUser) => {
    setPicked(user);
    setFocus(null);
    router.setParams({ user: user.id, handle: user.handle });
  };
  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: getAdminListRankingConfigsQueryKey() });

  const save = async () => {
    if (!selected || !editor) return;
    setBusy(true);
    setErrors([]);
    try {
      const saved =
        selected.status === 'DRAFT'
          ? await adminUpdateRankingConfig(selected.id, {
              weights: editor.weights,
              params: editor.params,
            })
          : await adminCreateRankingConfig({
              weights: editor.weights,
              params: editor.params,
              notes: `From v${selected.version} in the playground`,
            });
      await refresh();
      router.setParams({ config: saved.id });
    } catch (e) {
      setErrors(problemLines(e));
    } finally {
      setBusy(false);
    }
  };

  const confirm = async () => {
    if (!pending) return;
    setBusy(true);
    setErrors([]);
    try {
      await adminActivateRankingConfig(pending.target.id);
      await refresh();
      setPending(null);
    } catch (e) {
      setErrors(problemLines(e));
    } finally {
      setBusy(false);
    }
  };

  if (configs.status === 'pending') {
    return (
      <View style={styles.page} testID="ranking-loading">
        <Text variant="display">Ranking playground</Text>
        <Skeleton height={400} />
      </View>
    );
  }
  if (configs.status === 'error' || !active || !selected || !editor) {
    return (
      <View style={styles.page}>
        <Text variant="display">Ranking playground</Text>
        <StateMessage
          testID="ranking-error"
          title="Couldn't load the ranking configs"
          detail="The server didn't answer, or there is no active config."
          onRetry={() => void configs.refetch()}
        />
      </View>
    );
  }

  const canActivate = selected.status !== 'ACTIVE' && !dirty;
  const focused =
    focus && preview.data
      ? (focus.side === 'a' ? preview.data.a : preview.data.b)?.items.find(
          (i: RankingPreviewItem) => i.post.id === focus.postId,
        )
      : undefined;
  const draftTitle = `${label(selected)}${dirty ? ' + unsaved edits' : ''}`;

  return (
    <ScrollView contentContainerStyle={styles.page} testID="ranking-page">
      <Text variant="display">Ranking playground</Text>
      <View style={styles.main}>
        {/* ── left: configs and sliders ───────────────────────────────────────────────── */}
        <View
          style={[styles.left, { borderColor: colors.border, backgroundColor: colors.surface }]}
          testID="ranking-editor"
        >
          <ConfigList groups={groups} selectedId={selected.id} onSelect={select} />
          <Text variant="label" muted>
            WEIGHTS ({label(selected)})
          </Text>
          {weightKeys(editor.weights).map((name) => (
            <SliderRow
              key={name}
              name={name}
              label={name}
              value={editor.weights[name] ?? 0}
              range={WEIGHT_RANGE}
              changed={editor.weights[name] !== selected.weights[name]}
              lockedReason={name === LOCKED_WEIGHT ? LOCKED_REASON : undefined}
              onChange={(v) => edit((e) => ({ ...e, weights: { ...e.weights, [name]: v } }))}
            />
          ))}
          <Text variant="label" muted>
            PARAMS
          </Text>
          {PARAM_SLIDERS.map((p) => (
            <SliderRow
              key={p.key}
              name={p.key}
              label={p.label}
              value={editor.params[p.key] as number}
              range={p.range}
              changed={editor.params[p.key] !== selected.params[p.key]}
              onChange={(v) => edit((e) => ({ ...e, params: { ...e.params, [p.key]: v } }))}
            />
          ))}
          {errors.length ? (
            <View testID="ranking-errors">
              {errors.map((e) => (
                <Text key={e} variant="caption" style={{ color: colors.danger }}>
                  {e}
                </Text>
              ))}
            </View>
          ) : null}
          <View style={styles.buttons}>
            <Button
              title="Reset"
              size="small"
              variant="secondary"
              disabled={!dirty || busy}
              onPress={() => setEdits(null)}
              testID="ranking-reset"
            />
            <Button
              title={selected.status === 'DRAFT' ? 'Save draft' : 'Save as draft'}
              size="small"
              variant="secondary"
              disabled={busy || (selected.status === 'DRAFT' && !dirty)}
              onPress={() => void save()}
              testID="ranking-save"
            />
            <Button
              title="Activate"
              size="small"
              disabled={!canActivate || busy}
              onPress={() => setPending({ kind: 'activate', target: selected })}
              testID="ranking-activate"
            />
            <Button
              title={previous ? `Re-activate v${previous.version}` : 'Re-activate previous'}
              size="small"
              variant="outline"
              disabled={!previous || busy}
              onPress={() => previous && setPending({ kind: 'restore', target: previous })}
              testID="ranking-restore"
            />
          </View>
          {dirty && selected.status !== 'ACTIVE' ? (
            <Text variant="caption" muted>
              Save the edits as a draft before activating them.
            </Text>
          ) : null}
        </View>

        {/* ── centre: the two lists ──────────────────────────────────────────────────── */}
        <View style={styles.centre}>
          <UserPicker selected={picked} onPick={pickUser} />
          {!picked ? (
            <StateMessage
              testID="ranking-no-user"
              title="Pick a user to preview"
              detail="Their feed is ranked under the active config and under your edits, side by side."
            />
          ) : preview.status === 'error' ? (
            <StateMessage
              testID="preview-error"
              title="Couldn't rank this feed"
              detail={problemLines(preview.error).join(' · ')}
              onRetry={() => void preview.refetch()}
            />
          ) : !preview.data ? (
            <View testID="preview-loading" style={styles.columns}>
              <Skeleton height={480} />
              <Skeleton height={480} />
            </View>
          ) : (
            <View style={{ gap: spacing.sm, opacity: preview.updating ? 0.6 : 1 }}>
              <View style={styles.stats} testID="preview-stats">
                <Stat
                  label="Overlap@10"
                  value={metric(preview.data.overlapAt10, 2)}
                  testID="stat-overlap"
                />
                <Stat
                  label={`Kendall τ (${preview.data.commonItems} in both)`}
                  value={metric(preview.data.kendallTau, 3)}
                  testID="stat-tau"
                />
                <Stat label="Candidates" value={String(preview.data.candidates)} />
                <Pressable
                  role="checkbox"
                  aria-checked={exploration}
                  onPress={() => setExploration((x) => !x)}
                  testID="preview-exploration"
                >
                  <Text variant="caption">
                    {exploration ? '☑' : '☐'} Exploration (both lists share one seed)
                  </Text>
                </Pressable>
                {preview.updating ? (
                  <Text variant="caption" muted testID="preview-updating">
                    Updating…
                  </Text>
                ) : null}
              </View>
              <View style={styles.columns}>
                <PreviewColumn
                  title={`Active (${label(active)})`}
                  items={preview.data.a.items}
                  showChange={false}
                  selectedPostId={focus?.side === 'a' ? focus.postId : null}
                  onSelect={(i) => setFocus({ side: 'a', postId: i.post.id })}
                  testID="preview-a"
                />
                <PreviewColumn
                  title={draftTitle}
                  items={preview.data.b?.items ?? []}
                  showChange
                  selectedPostId={focus?.side === 'b' ? focus.postId : null}
                  onSelect={(i) => setFocus({ side: 'b', postId: i.post.id })}
                  testID="preview-b"
                />
              </View>
            </View>
          )}
        </View>

        {focused ? (
          <WaterfallDrawer
            item={focused}
            configLabel={focus?.side === 'a' ? `Active (${label(active)})` : draftTitle}
            onClose={() => setFocus(null)}
          />
        ) : null}
      </View>

      <ReplayPanel
        active={{ label: `Active ${label(active)}`, config: { id: active.id } }}
        draft={{ label: draftTitle, config: { weights: editor.weights, params: editor.params } }}
      />

      <ConfirmDialog
        visible={pending !== null}
        title={
          pending?.kind === 'restore'
            ? `Re-activate v${pending.target.version}?`
            : `Activate v${pending?.target.version ?? ''}?`
        }
        changes={
          pending
            ? [
                `Version ${pending.target.version} becomes the active ranking config`,
                `Version ${active.version} is archived (it can be re-activated)`,
                'New feed sessions use it; sessions already open keep theirs',
                ...(() => {
                  const diff = configDiff(editable(active), editable(pending.target));
                  return diff.length
                    ? diff
                    : ['Its weights and params are the same as the active one'];
                })(),
              ]
            : []
        }
        confirmLabel={pending?.kind === 'restore' ? 'Re-activate' : 'Activate'}
        busy={busy}
        error={pending && errors.length ? errors.join(' · ') : null}
        onConfirm={() => void confirm()}
        onCancel={() => {
          setPending(null);
          setErrors([]);
        }}
      />
    </ScrollView>
  );
}

function ConfigList({
  groups,
  selectedId,
  onSelect,
}: {
  groups: ReturnType<typeof groupConfigs>;
  selectedId: string;
  onSelect: (config: RankingConfigView) => void;
}) {
  const { colors } = useTheme();
  const sections: { title: string; items: RankingConfigView[] }[] = [
    { title: 'Active', items: groups.active ? [groups.active] : [] },
    { title: 'Drafts', items: groups.drafts },
    { title: 'Archived', items: groups.archived },
  ];
  return (
    <View style={styles.configs} testID="config-list">
      {sections.map((section) => (
        <View key={section.title} style={styles.section}>
          <Text variant="label" muted>
            {section.title.toUpperCase()}
          </Text>
          {section.items.length === 0 ? (
            <Text variant="caption" muted>
              None
            </Text>
          ) : null}
          {section.items.map((c) => {
            const lab = labSummary(c);
            return (
              <Pressable
                key={c.id}
                accessibilityRole="button"
                onPress={() => onSelect(c)}
                testID={`config-v${c.version}`}
                style={[
                  styles.config,
                  { borderColor: c.id === selectedId ? colors.accent : colors.border },
                  c.id === selectedId ? { backgroundColor: colors.accentSoft } : null,
                ]}
              >
                <Text variant="caption">
                  v{c.version} · {c.source}
                  {c.createdByHandle ? ` · @${c.createdByHandle}` : ''} · {c.createdAt.slice(0, 10)}
                </Text>
                {c.notes ? (
                  <Text variant="caption" muted numberOfLines={1}>
                    {c.notes}
                  </Text>
                ) : null}
                {lab ? (
                  <Text variant="caption" style={{ color: colors.accent2 }} numberOfLines={2}>
                    {lab}
                  </Text>
                ) : null}
              </Pressable>
            );
          })}
        </View>
      ))}
    </View>
  );
}

function Stat({ label, value, testID }: { label: string; value: string; testID?: string }) {
  return (
    <View>
      <Text variant="caption" muted>
        {label}
      </Text>
      <Text variant="title" testID={testID}>
        {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md, paddingBottom: spacing.xl },
  main: { flexDirection: 'row', gap: spacing.md, alignItems: 'flex-start' },
  left: {
    width: 400,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
  },
  centre: { flex: 1, gap: spacing.md, minWidth: 0 },
  columns: { flexDirection: 'row', gap: spacing.md },
  stats: { flexDirection: 'row', alignItems: 'flex-end', gap: spacing.lg, flexWrap: 'wrap' },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.sm },
  configs: { gap: spacing.sm, marginBottom: spacing.sm },
  section: { gap: spacing.xs },
  config: { borderWidth: 1, borderRadius: radius.sm, padding: spacing.xs },
});
