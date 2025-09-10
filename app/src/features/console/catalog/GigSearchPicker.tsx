import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import { useGetGig, useListGigs } from '@/api/generated/catalog/catalog';
import type { GigSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { Text } from '@/components/ui/Text';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { billLine, gigLine, isGigId } from './mergeModel';

const YEAR = 365 * 24 * 60 * 60 * 1000;

/** Two years back to one ahead: duplicates are found around gigs people post from. Fixed per picker. */
export function searchWindow(now: Date): { from: string; to: string } {
  return {
    from: new Date(now.getTime() - 2 * YEAR).toISOString(),
    to: new Date(now.getTime() + YEAR).toISOString(),
  };
}

/**
 * Picks one gig for the merge, with the app's own gig search (`GET /gigs`, nationwide, newest first)
 * or a pasted gig id. Shows the picked gig with a "Change" button.
 */
export function GigSearchPicker({
  label,
  value,
  onChange,
  testID,
}: {
  label: string;
  value: GigSummary | null;
  onChange: (gig: GigSummary | null) => void;
  testID: string;
}) {
  const { colors } = useTheme();
  const [window] = useState(() => searchWindow(new Date()));
  const [text, setText] = useState('');
  const q = useDebounced(text.trim(), 300);
  const byId = isGigId(q);
  const searching = !byId && q.length >= 2;

  const found = useListGigs(
    { ...window, q, nationwide: true, order: 'desc', limit: 10 },
    { query: { enabled: searching && value === null } },
  );
  const pasted = useGetGig(q, undefined, { query: { enabled: byId && value === null } });

  if (value) {
    return (
      <View style={styles.box} testID={testID}>
        <Text variant="label" muted>
          {label.toUpperCase()}
        </Text>
        <Text>{gigLine(value)}</Text>
        <Text variant="caption" muted>
          {billLine(value)}
        </Text>
        <Button
          title="Change"
          variant="secondary"
          size="small"
          onPress={() => onChange(null)}
          testID={`${testID}-change`}
        />
      </View>
    );
  }

  const results: GigSummary[] = byId
    ? pasted.data
      ? [pasted.data.gig]
      : []
    : searching
      ? (found.data?.items ?? [])
      : [];
  const loading = byId ? pasted.isFetching : searching && found.isFetching;
  const failed = byId ? pasted.isError : searching && found.isError;

  return (
    <View style={styles.box} testID={testID}>
      <Text variant="label" muted>
        {label.toUpperCase()}
      </Text>
      <TextInput
        accessibilityLabel={label}
        placeholder="Search by act or title, or paste a gig id"
        placeholderTextColor={colors.textMuted}
        value={text}
        onChangeText={setText}
        testID={`${testID}-search`}
        style={[
          styles.input,
          { color: colors.text, borderColor: colors.border, backgroundColor: colors.surface },
        ]}
      />
      {loading ? <Skeleton height={44} /> : null}
      {failed ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {byId ? 'No gig has that id.' : "The search didn't answer. Try again."}
        </Text>
      ) : null}
      {!loading && !failed && (searching || byId) && results.length === 0 ? (
        <Text variant="caption" muted>
          No gig matches &quot;{q}&quot;.
        </Text>
      ) : null}
      {results.map((gig) => (
        <Pressable
          key={gig.id}
          accessibilityRole="button"
          onPress={() => onChange(gig)}
          testID={`${testID}-result-${gig.id}`}
          style={[styles.result, { borderColor: colors.border }]}
        >
          <Text>{gigLine(gig)}</Text>
          <Text variant="caption" muted>
            {billLine(gig)}
            {gig.userAdded ? ' · added by a fan' : ''}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { flex: 1, gap: spacing.sm, minWidth: 320 },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    fontSize: 16,
  },
  result: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.sm,
    gap: 2,
  },
});
