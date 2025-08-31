import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import { adminListUsers, getAdminListUsersQueryKey } from '@/api/generated/admin-users/admin-users';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/** As the Users page's search: wait for typing to stop. */
const SEARCH_DEBOUNCE_MS = 300;

export type PickedUser = { id: string; handle: string; synthetic?: boolean };

/**
 * Pick whose feed to look at: search by handle, name or exact email; seed and simulator
 * personas carry a SYNTHETIC tag, so nobody mistakes them for real people.
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
  const [open, setOpen] = useState(selected === null);
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [text]);

  const params = { limit: 8, ...(q ? { q } : { synthetic: true }) };
  const users = useQuery({
    queryKey: getAdminListUsersQueryKey(params),
    queryFn: ({ signal }) => adminListUsers(params, { signal }),
    enabled: open,
  });

  if (!open && selected) {
    return (
      <View style={styles.selected} testID={testID}>
        <Text variant="caption" muted>
          Feed of
        </Text>
        <Text testID={`${testID}-selected`}>@{selected.handle}</Text>
        {selected.synthetic ? <SyntheticTag /> : null}
        <Pressable
          accessibilityRole="button"
          onPress={() => setOpen(true)}
          testID={`${testID}-change`}
        >
          <Text variant="caption" style={{ color: colors.accent }}>
            Change
          </Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={styles.box} testID={testID}>
      <TextInput
        accessibilityLabel="Find a user"
        placeholder="Find a user: handle, name or exact email (personas listed first)"
        placeholderTextColor={colors.textMuted}
        value={text}
        onChangeText={setText}
        testID={`${testID}-search`}
        style={[
          styles.search,
          { color: colors.text, borderColor: colors.border, backgroundColor: colors.surface },
        ]}
      />
      <View style={[styles.results, { borderColor: colors.border }]}>
        {users.status === 'pending' ? (
          <Text variant="caption" muted>
            Searching…
          </Text>
        ) : users.status === 'error' ? (
          <Text variant="caption" style={{ color: colors.danger }}>
            Couldn&apos;t search users.
          </Text>
        ) : users.data.items.length === 0 ? (
          <Text variant="caption" muted>
            {q ? `No account matches "${q}"` : 'No personas yet: search for anyone'}
          </Text>
        ) : (
          users.data.items.map((u) => (
            <Pressable
              key={u.id}
              accessibilityRole="button"
              onPress={() => {
                onPick({ id: u.id, handle: u.handle, synthetic: u.synthetic });
                setOpen(false);
              }}
              testID={`${testID}-option-${u.handle}`}
              style={styles.option}
            >
              <Text>@{u.handle}</Text>
              <Text variant="caption" muted numberOfLines={1} style={styles.name}>
                {u.displayName}
              </Text>
              {u.synthetic ? <SyntheticTag /> : null}
            </Pressable>
          ))
        )}
        {selected ? (
          <Pressable accessibilityRole="button" onPress={() => setOpen(false)}>
            <Text variant="caption" style={{ color: colors.accent }}>
              Keep @{selected.handle}
            </Text>
          </Pressable>
        ) : null}
      </View>
    </View>
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
  results: { borderWidth: 1, borderRadius: radius.md, padding: spacing.sm, gap: spacing.xs },
  option: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: 2 },
  name: { flexShrink: 1 },
  tag: { borderRadius: radius.sm, paddingHorizontal: spacing.sm, paddingVertical: 2 },
});
