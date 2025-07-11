import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import {
  getListSavedGigFiltersQueryKey,
  useDeleteGigFilter,
  useListSavedGigFilters,
  useSaveGigFilter,
  useSetGigFilterAlerts,
} from '@/api/generated/catalog/catalog';
import type { ApiError } from '@/api/http';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { fromSaved, isNarrowed, suggestName, toSaveRequest, type GigSearch } from './gigFilters';

/**
 * Saved searches on the Gigs tab: one tap brings back "Keo, Bleech 9:3 and The North in London".
 * "Save this search" is always there, greyed out until the search is narrowed (an artist, a venue, a
 * genre or another place), so the row never jumps when it becomes possible; "Edit" shows a cross on
 * each saved one to delete it, and a bell to switch its new-gig alerts off or on (on by default; a
 * saved search with alerts shows a small bell).
 */
export function SavedFilters({
  search,
  onApply,
}: {
  search: GigSearch;
  onApply: (search: GigSearch) => void;
}) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const saved = useListSavedGigFilters();
  const save = useSaveGigFilter<ApiError>();
  const remove = useDeleteGigFilter();
  const alerts = useSetGigFilterAlerts();
  const [naming, setNaming] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const refresh = () => client.invalidateQueries({ queryKey: getListSavedGigFiltersQueryKey() });

  const filters = saved.data ?? [];
  const canSave = isNarrowed(search) && naming === null && !editing;
  return (
    <View testID="saved-filters">
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={styles.row}
        keyboardShouldPersistTaps="handled"
      >
        {filters.map((f) => (
          <View key={f.id} style={styles.pair}>
            {editing ? (
              <Pressable
                accessibilityRole="switch"
                accessibilityState={{ checked: f.alerts }}
                accessibilityLabel={
                  f.alerts
                    ? `Turn off new-gig alerts for ${f.name}`
                    : `Turn on new-gig alerts for ${f.name}`
                }
                onPress={() =>
                  void alerts
                    .mutateAsync({ id: f.id, data: { alerts: !f.alerts } })
                    .then(refresh, () => undefined)
                }
                style={[styles.bell, { borderColor: colors.border }]}
                testID="saved-search-alerts"
              >
                <Ionicons
                  name={f.alerts ? 'notifications' : 'notifications-off-outline'}
                  size={16}
                  color={f.alerts ? colors.accent : colors.textMuted}
                />
              </Pressable>
            ) : null}
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={
                editing ? `Delete saved search ${f.name}` : `Saved search: ${f.name}`
              }
              onPress={() =>
                editing
                  ? void remove.mutateAsync({ id: f.id }).then(refresh, () => undefined)
                  : onApply(fromSaved(f))
              }
              style={[
                styles.chip,
                { backgroundColor: colors.accentSoft, borderColor: colors.accent },
              ]}
            >
              <Ionicons
                name={editing ? 'close-circle' : 'bookmark'}
                size={14}
                color={colors.accent}
              />
              <Text variant="caption" style={styles.bold} numberOfLines={1}>
                {f.name}
              </Text>
              {!editing && f.alerts ? (
                <Ionicons
                  name="notifications"
                  size={12}
                  color={colors.accent}
                  accessibilityLabel="New-gig alerts on"
                />
              ) : null}
            </Pressable>
          </View>
        ))}
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Save this search"
          accessibilityHint={
            canSave ? undefined : 'Pick an artist, a venue, a genre or another place first'
          }
          accessibilityState={{ disabled: !canSave }}
          aria-disabled={!canSave}
          disabled={!canSave}
          onPress={() => setNaming(suggestName(search))}
          style={[
            styles.chip,
            { backgroundColor: colors.surfaceRaised, borderColor: colors.border },
            !canSave && styles.unavailable,
          ]}
          testID="save-search"
        >
          <Ionicons
            name="bookmark-outline"
            size={14}
            color={canSave ? colors.text : colors.textMuted}
          />
          <Text variant="caption" muted={!canSave}>
            Save this search
          </Text>
        </Pressable>
        {filters.length > 0 ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={editing ? 'Done editing saved searches' : 'Edit saved searches'}
            onPress={() => setEditing((on) => !on)}
            style={[styles.chip, { borderColor: colors.border }]}
          >
            <Text variant="caption" muted>
              {editing ? 'Done' : 'Edit'}
            </Text>
          </Pressable>
        ) : null}
      </ScrollView>
      {naming !== null ? (
        <View style={styles.form}>
          <TextInput
            accessibilityLabel="Name of the saved search"
            value={naming}
            onChangeText={setNaming}
            maxLength={60}
            autoFocus
            style={[
              styles.input,
              { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
            ]}
          />
          <Button
            title={save.isPending ? 'Saving…' : 'Save'}
            disabled={naming.trim() === '' || save.isPending}
            onPress={() =>
              void save
                .mutateAsync({ data: toSaveRequest(naming.trim(), search) })
                .then(() => {
                  setNaming(null);
                  return refresh();
                })
                .catch(() => undefined)
            }
          />
          <Button title="Cancel" variant="secondary" onPress={() => setNaming(null)} />
        </View>
      ) : null}
      {save.error ? (
        <Text variant="caption" style={{ color: colors.danger }} testID="save-search-error">
          {save.error.code === 'SAVED_FILTERS_FULL'
            ? 'You have 20 saved searches. Delete one first (Edit).'
            : 'Could not save the search. Try again.'}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { marginTop: spacing.sm, flexGrow: 0 },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    minHeight: touchTarget,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    marginRight: spacing.sm,
    maxWidth: 260,
  },
  bold: { fontWeight: '700' },
  unavailable: { opacity: 0.5 },
  pair: { flexDirection: 'row', alignItems: 'center' },
  bell: {
    minHeight: touchTarget,
    minWidth: touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    marginRight: spacing.xs,
  },
  form: { marginTop: spacing.sm, gap: spacing.sm },
  input: {
    minHeight: touchTarget,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    fontSize: 16,
  },
});
