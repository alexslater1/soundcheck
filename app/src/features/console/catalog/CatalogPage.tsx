import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { CATALOG_TABS, FAN_GIGS } from './tabs';
import type { MergePair, VenueMergeDone } from './tabTypes';

/**
 * The console's Catalog page: a tab per curation job, listed in `tabs.ts`: Fan gigs (the queue
 * of gigs fans added), Merge (two gigs side by side, then one transaction), Matches, Venues and
 * Edit history.
 */
export default function CatalogPage() {
  const { colors } = useTheme();
  // `?tab=matches` opens a tab directly (the Ingestion page links to Matches and Venues).
  const { tab: asked } = useLocalSearchParams<{ tab?: string }>();
  const [active, setActive] = useState(
    CATALOG_TABS.find((t) => t.key === asked)?.key ?? FAN_GIGS.key,
  );
  const [pair, setPair] = useState<MergePair | null>(null);
  const [venueMerge, setVenueMerge] = useState<VenueMergeDone | null>(null);
  const [mergedGigIds, setMergedGigIds] = useState<ReadonlySet<string>>(new Set());
  const tab = CATALOG_TABS.find((t) => t.key === active) ?? FAN_GIGS;

  const openMerge = (from: GigSummary, into: GigSummary) => {
    setPair((p) => ({ from, into, nonce: (p?.nonce ?? 0) + 1 }));
    setActive('merge');
  };
  const onGigMerged = (fromId: string) => setMergedGigIds((prev) => new Set(prev).add(fromId));

  return (
    <View style={styles.page}>
      <Text variant="display">Catalog</Text>
      <View
        style={[styles.tabs, { borderBottomColor: colors.border }]}
        role="tablist"
        testID="catalog-tabs"
      >
        {CATALOG_TABS.map((t) => {
          const selected = t.key === tab.key;
          return (
            <Pressable
              key={t.key}
              role="tab"
              aria-selected={selected}
              onPress={() => setActive(t.key)}
              testID={`catalog-tab-${t.key}`}
              style={[styles.tab, { borderBottomColor: selected ? colors.accent : 'transparent' }]}
            >
              <Text variant="label" muted={!selected}>
                {t.label.toUpperCase()}
              </Text>
            </Pressable>
          );
        })}
      </View>
      <tab.Component
        key={tab.key === 'merge' ? `merge-${pair?.nonce ?? 0}` : tab.key}
        openMerge={openMerge}
        pair={pair}
        openTab={setActive}
        venueMerge={venueMerge}
        setVenueMerge={setVenueMerge}
        mergedGigIds={mergedGigIds}
        onGigMerged={onGigMerged}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  tabs: { flexDirection: 'row', gap: spacing.md, borderBottomWidth: 1 },
  tab: { paddingVertical: spacing.sm, borderBottomWidth: 2 },
});
