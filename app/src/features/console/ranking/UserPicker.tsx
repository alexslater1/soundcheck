import { useInfiniteQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { adminListUsers, getAdminListUsersQueryKey } from '@/api/generated/admin-users/admin-users';
import type { AdminListUsersParams, AdminUser } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/** As the Users page's search: wait for typing to stop. */
const SEARCH_DEBOUNCE_MS = 300;
/** Accounts per page of the list; the API's maximum. */
const PAGE_SIZE = 50;

export type PickedUser = { id: string; handle: string; synthetic?: boolean };

/**
 * Pick whose feed to look at. The list is always open and scrolls: with no search it shows
 * the seed and simulator personas first (tagged SYNTHETIC, so nobody mistakes them for real people), then
 * every other account; typing searches by handle, name or exact email. The current pick is highlighted.
 */
export function UserPicker({
  selected,
  onPick,
  testID = 'user-picker',
}: {
  selected: PickedUser | null;
  onPick: (user: PickedUser) => void;
  testID?: string;
}) {
  const { colors } = useTheme();
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [text]);

  // No search: personas, then the rest. A search: one list of whatever matches.
  const personas = useUserPages(q ? { q } : { synthetic: true });
  const others = useUserPages({ synthetic: false }, !q);

  return (
    <View style={styles.box} testID={testID}>
      <View style={styles.selected}>
        <Text variant="caption" muted>
          Feed of
        </Text>
        {selected ? (
          <>
            <Text testID={`${testID}-selected`}>@{selected.handle}</Text>
            {selected.synthetic ? <SyntheticTag /> : null}
          </>
        ) : (
          <Text variant="caption" muted>
            nobody yet: pick an account below
          </Text>
        )}
      </View>
      <TextInput
        accessibilityLabel="Find a user"
        placeholder="Filter: handle, name or exact email"
        placeholderTextColor={colors.textMuted}
        value={text}
        onChangeText={setText}
        testID={`${testID}-search`}
        style={[
          styles.search,
          { color: colors.text, borderColor: colors.border, backgroundColor: colors.surface },
        ]}
      />
      <ScrollView
        style={[styles.results, { borderColor: colors.border }]}
        contentContainerStyle={styles.resultsContent}
        testID={`${testID}-list`}
      >
        {q ? null : (
          <Text variant="label" muted>
            PERSONAS
          </Text>
        )}
        <Section
          pages={personas}
          empty={q ? `No account matches "${q}"` : 'No personas yet'}
          selectedId={selected?.id}
          onPick={onPick}
          testID={testID}
        />
        {q ? null : (
          <>
            <Text variant="label" muted style={styles.heading}>
              OTHER ACCOUNTS
            </Text>
            <Section
              pages={others}
              empty="No other accounts"
              selectedId={selected?.id}
              onPick={onPick}
              testID={testID}
            />
          </>
        )}
      </ScrollView>
    </View>
  );
}

type UserPages = ReturnType<typeof useUserPages>;

/** One filter's accounts, a page at a time (keyset cursor from the API). */
function useUserPages(params: AdminListUsersParams, enabled = true) {
  return useInfiniteQuery({
    queryKey: [...getAdminListUsersQueryKey({ ...params, limit: PAGE_SIZE }), 'picker'],
    queryFn: ({ pageParam, signal }) =>
      adminListUsers(
        { ...params, limit: PAGE_SIZE, ...(pageParam ? { cursor: pageParam } : {}) },
        { signal },
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  });
}

function Section({
  pages,
  empty,
  selectedId,
  onPick,
  testID,
}: {
  pages: UserPages;
  empty: string;
  selectedId: string | undefined;
  onPick: (user: PickedUser) => void;
  testID: string;
}) {
  const { colors } = useTheme();
  if (pages.status === 'pending') {
    return (
      <Text variant="caption" muted>
        Loading…
      </Text>
    );
  }
  if (pages.status === 'error') {
    return (
      <Text variant="caption" style={{ color: colors.danger }}>
        Couldn&apos;t load the accounts.
      </Text>
    );
  }
  const users: AdminUser[] = pages.data.pages.flatMap((p) => p.items);
  if (users.length === 0) {
    return (
      <Text variant="caption" muted>
        {empty}
      </Text>
    );
  }
  return (
    <>
      {users.map((u) => {
        const current = u.id === selectedId;
        return (
          <Pressable
            key={u.id}
            accessibilityRole="button"
            aria-selected={current}
            onPress={() => onPick({ id: u.id, handle: u.handle, synthetic: u.synthetic })}
            testID={`${testID}-option-${u.handle}`}
            style={[
              styles.option,
              current ? { backgroundColor: colors.accent2Soft, borderRadius: radius.sm } : null,
            ]}
          >
            <Text style={current ? { fontWeight: '600' } : null}>@{u.handle}</Text>
            <Text variant="caption" muted numberOfLines={1} style={styles.name}>
              {u.displayName}
            </Text>
            {u.synthetic ? <SyntheticTag /> : null}
          </Pressable>
        );
      })}
      {pages.hasNextPage ? (
        <Pressable
          accessibilityRole="button"
          onPress={() => void pages.fetchNextPage()}
          disabled={pages.isFetchingNextPage}
        >
          <Text variant="caption" style={{ color: colors.accent }}>
            {pages.isFetchingNextPage ? 'Loading…' : 'More accounts'}
          </Text>
        </Pressable>
      ) : null}
    </>
  );
}

export function SyntheticTag() {
  const { colors } = useTheme();
  return (
    <View style={[styles.tag, { backgroundColor: colors.accent2Soft }]} testID="synthetic-tag">
      <Text variant="label">SYNTHETIC</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.xs, maxWidth: 560 },
  selected: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  search: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    fontSize: 15,
  },
  // About eight rows; the rest scroll.
  results: { borderWidth: 1, borderRadius: radius.md, maxHeight: 260 },
  resultsContent: { padding: spacing.sm, gap: spacing.xs },
  heading: { marginTop: spacing.sm },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: 2,
    paddingHorizontal: spacing.xs,
  },
  name: { flexShrink: 1 },
  tag: { borderRadius: radius.sm, paddingHorizontal: spacing.sm, paddingVertical: 2 },
});
