import { useInfiniteQuery } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import { adminListUsers, getAdminListUsersQueryKey } from '@/api/generated/admin-users/admin-users';
import type { AdminUser } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { DataTable, type Column } from './DataTable';

type Kind = 'all' | 'real' | 'synthetic';

const KINDS: { id: Kind; label: string }[] = [
  { id: 'all', label: 'Everyone' },
  { id: 'real', label: 'Real' },
  { id: 'synthetic', label: 'Synthetic' },
];

/** How long the search box waits for typing to stop before it asks the server. */
export const SEARCH_DEBOUNCE_MS = 300;

/**
 * The console's Users page: find an account by handle, name or exact email, filter real
 * or synthetic, and jump to the feed inspector or the user's reports. Read-only: no suspend or ban.
 */
export default function UsersPage() {
  const { colors } = useTheme();
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const [kind, setKind] = useState<Kind>('all');
  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [text]);

  const params = {
    ...(q ? { q } : {}),
    ...(kind === 'all' ? {} : { synthetic: kind === 'synthetic' }),
  };
  const query = useInfiniteQuery({
    queryKey: [...getAdminListUsersQueryKey(params), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      adminListUsers({ ...params, ...(pageParam ? { cursor: pageParam } : {}) }, { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const rows = query.data?.pages.flatMap((p) => p.items);

  const columns: Column<AdminUser>[] = [
    {
      key: 'handle',
      title: 'Account',
      flex: 2,
      sortValue: (u) => u.handle,
      render: (u) => (
        <View>
          <Text>
            @{u.handle}
            {u.role === 'ADMIN' ? '  · admin' : ''}
          </Text>
          <Text variant="caption" muted>
            {u.displayName}
          </Text>
        </View>
      ),
    },
    {
      key: 'email',
      title: 'Email',
      flex: 2,
      sortValue: (u) => u.email,
      render: (u) => (
        <Text variant="caption" numberOfLines={1}>
          {u.email}
        </Text>
      ),
    },
    {
      key: 'synthetic',
      title: 'Kind',
      sortValue: (u) => u.synthetic,
      render: (u) =>
        u.synthetic ? (
          <View
            style={[styles.tag, { backgroundColor: colors.accent2Soft }]}
            testID="synthetic-tag"
          >
            <Text variant="label">SYNTHETIC</Text>
          </View>
        ) : (
          <Text variant="caption" muted>
            Real
          </Text>
        ),
    },
    {
      key: 'posts',
      title: 'Posts',
      align: 'right',
      sortValue: (u) => u.posts,
      render: (u) => <Text>{u.posts}</Text>,
    },
    {
      key: 'created',
      title: 'Joined',
      sortValue: (u) => u.createdAt,
      render: (u) => <Text variant="caption">{u.createdAt.slice(0, 10)}</Text>,
    },
    {
      key: 'links',
      title: '',
      flex: 2,
      render: (u) => (
        <View style={styles.links}>
          <Link href={{ pathname: '/console/inspector', params: { user: u.id } }}>
            <Text variant="caption" style={{ color: colors.accent }}>
              Inspect feed
            </Text>
          </Link>
          <Link href={{ pathname: '/console/moderation', params: { user: u.id } }}>
            <Text variant="caption" style={{ color: colors.accent }}>
              Reports
            </Text>
          </Link>
          <Link href={{ pathname: '/u/[handle]', params: { handle: u.handle } }}>
            <Text variant="caption" style={{ color: colors.accent }}>
              Profile
            </Text>
          </Link>
        </View>
      ),
    },
  ];

  return (
    <View style={styles.page}>
      <Text variant="display">Users</Text>
      <View style={styles.filters}>
        <TextInput
          accessibilityLabel="Search users"
          placeholder="Handle, name or exact email"
          placeholderTextColor={colors.textMuted}
          value={text}
          onChangeText={setText}
          testID="users-search"
          style={[
            styles.search,
            { color: colors.text, borderColor: colors.border, backgroundColor: colors.surface },
          ]}
        />
        <View style={styles.kinds} role="radiogroup" accessibilityLabel="Kind of account">
          {KINDS.map((k) => (
            <Pressable
              key={k.id}
              role="radio"
              aria-checked={kind === k.id}
              onPress={() => setKind(k.id)}
              testID={`users-kind-${k.id}`}
              style={[
                styles.kind,
                { borderColor: colors.border },
                kind === k.id
                  ? { backgroundColor: colors.accentSoft, borderColor: colors.accent }
                  : null,
              ]}
            >
              <Text variant="caption">{k.label}</Text>
            </Pressable>
          ))}
        </View>
      </View>
      <DataTable
        testID="users-table"
        columns={columns}
        rows={rows}
        rowKey={(u) => u.id}
        status={query.status}
        onRetry={() => void query.refetch()}
        emptyTitle={q ? `No account matches "${q}"` : 'No accounts yet'}
        emptyDetail={
          q ? 'Searches handles and display names; an email must match exactly.' : undefined
        }
        hasMore={query.hasNextPage}
        loadingMore={query.isFetchingNextPage}
        onLoadMore={() => void query.fetchNextPage()}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  filters: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
  search: {
    flex: 1,
    maxWidth: 420,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    fontSize: 16,
  },
  kinds: { flexDirection: 'row', gap: spacing.xs },
  kind: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 4,
  },
  tag: {
    alignSelf: 'flex-start',
    borderRadius: radius.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
  },
  links: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' },
});
