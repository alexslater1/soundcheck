import { StyleSheet, View } from 'react-native';

import type { GigMergePreview, GigMergeSide } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { statusLabel } from '@/features/gigs/format';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { billLine, countLabel, gigLine, mergeWarnings } from './mergeModel';
import { who } from './useHandles';

/**
 * The merge preview: the duplicate and the gig that stays side by side, what will move, and the
 * conflicts that refuse the merge. Pure rendering; the Merge button opens the confirm dialog upstream.
 */
export function MergePreview({
  preview,
  handles,
  onMerge,
}: {
  preview: GigMergePreview;
  handles: Record<string, string>;
  onMerge: () => void;
}) {
  const { colors } = useTheme();
  const warnings = mergeWarnings(preview);
  const blocked = preview.conflicts.length > 0;
  return (
    <View style={styles.wrap} testID="merge-preview">
      <View style={styles.sides}>
        <Side
          title="Duplicate (deleted)"
          side={preview.from}
          handles={handles}
          testID="merge-from"
        />
        <Side title="Stays" side={preview.into} handles={handles} testID="merge-into" />
      </View>
      {warnings.map((w) => (
        <Text key={w} style={{ color: colors.warning }} testID="merge-warning">
          ⚠ {w}
        </Text>
      ))}
      {blocked ? (
        <View
          style={[styles.box, { borderColor: colors.danger }]}
          accessibilityRole="alert"
          testID="merge-conflicts"
        >
          <Text variant="label" style={{ color: colors.danger }}>
            REFUSED UNTIL THESE ARE FIXED
          </Text>
          {preview.conflicts.map((c) => (
            <Text key={c}>• {c}</Text>
          ))}
        </View>
      ) : null}
      <View style={[styles.box, { borderColor: colors.border }]} testID="merge-moving">
        <Text variant="label" muted>
          WHAT MOVES TO THE GIG THAT STAYS
        </Text>
        {preview.moving.filter((m) => m.count > 0).length === 0 ? (
          <Text muted>Nothing: the duplicate has no marks, posts or listings.</Text>
        ) : (
          preview.moving
            .filter((m) => m.count > 0)
            .map((m) => <Text key={m.what}>• {countLabel(m)}</Text>)
        )}
      </View>
      <View style={styles.actions}>
        <Button
          title="Merge…"
          variant="danger"
          disabled={blocked}
          onPress={onMerge}
          testID="merge-start"
        />
        {blocked ? (
          <Text variant="caption" muted>
            Fix the conflicts above, then pick the gigs again.
          </Text>
        ) : null}
      </View>
    </View>
  );
}

function Side({
  title,
  side,
  handles,
  testID,
}: {
  title: string;
  side: GigMergeSide;
  handles: Record<string, string>;
  testID: string;
}) {
  const { colors } = useTheme();
  const { gig } = side;
  const status = statusLabel(gig.status);
  return (
    <View style={[styles.side, { borderColor: colors.border }]} testID={testID}>
      <Text variant="label" muted>
        {title.toUpperCase()}
      </Text>
      <Text variant="title">{gig.title}</Text>
      <Text>{gigLine(gig)}</Text>
      {status ? <Text style={{ color: colors.warning }}>{status}</Text> : null}
      <Text variant="caption" muted>
        Bill: {billLine(gig)}
      </Text>
      <Text variant="caption" muted>
        {side.addedBy ? `Added by ${who(handles, side.addedBy)}` : 'From a source listing'}
      </Text>
      <Text variant="label" muted style={styles.sub}>
        LISTINGS
      </Text>
      {side.listings.length === 0 ? (
        <Text variant="caption" muted>
          None
        </Text>
      ) : (
        side.listings.map((l) => (
          <Text key={`${l.source}:${l.externalId}`} variant="caption">
            {l.source} {l.externalId}
            {l.primary ? ' · primary' : ''}
          </Text>
        ))
      )}
      <Text variant="label" muted style={styles.sub}>
        HAS
      </Text>
      {side.counts
        .filter((c) => c.count > 0)
        .map((c) => (
          <Text key={c.what} variant="caption">
            {countLabel(c)}
          </Text>
        ))}
      <Text variant="caption" muted selectable>
        {gig.id}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.md },
  sides: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' },
  side: {
    flex: 1,
    minWidth: 320,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
  },
  sub: { marginTop: spacing.sm },
  box: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.xs },
  actions: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
});
