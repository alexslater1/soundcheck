import Ionicons from '@expo/vector-icons/Ionicons';
import { Link } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { useDuplicateNotices } from './duplicates';

/**
 * The top of the Create tab when a picked clip is already in one of the user's posts:
 * the server refused its upload, the clip left the list, and this says why, with a link to that post.
 */
export function DuplicateNotices({ ownerId }: { ownerId: string }) {
  const { colors } = useTheme();
  const notices = useDuplicateNotices((state) => state.notices).filter(
    (notice) => notice.ownerId === ownerId,
  );
  const dismiss = useDuplicateNotices((state) => state.dismiss);
  if (notices.length === 0) {
    return null;
  }
  return (
    <View style={styles.list}>
      {notices.map((notice) => (
        <View
          key={notice.localId}
          accessibilityRole="alert"
          style={[styles.notice, { backgroundColor: colors.surface, borderColor: colors.border }]}
          testID={`duplicate-${notice.localId}`}
        >
          <View style={styles.head}>
            <Ionicons name="copy-outline" size={20} color={colors.textMuted} />
            <Text style={styles.grow}>
              You’ve posted this clip already
              {notice.fileName ? ` (${notice.fileName})` : ''}.
            </Text>
          </View>
          <View style={styles.actions}>
            <Link href={{ pathname: '/post/[id]', params: { id: notice.postId } }} asChild>
              <Button
                title="See the post"
                size="small"
                accessibilityRole="link"
                testID={`duplicate-${notice.localId}-post`}
              />
            </Link>
            <Button
              title="OK"
              variant="secondary"
              size="small"
              onPress={() => dismiss(notice.localId)}
              testID={`duplicate-${notice.localId}-dismiss`}
            />
          </View>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  list: { gap: spacing.sm },
  notice: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  head: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  grow: { flex: 1 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
});
