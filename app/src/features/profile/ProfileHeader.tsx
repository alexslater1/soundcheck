import { Image } from 'expo-image';
import { Link } from 'expo-router';
import type { PropsWithChildren } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

type Props = PropsWithChildren<{
  handle: string;
  displayName: string;
  avatarUrl?: string | null;
  bio?: string | null;
  detail?: string | null;
  counts: { posts: number; followers: number; following: number };
  /** False for a private profile the viewer may not see: the counts show, the lists do not open. */
  listsOpen?: boolean;
}>;

/** The top of a profile (one's own or someone else's): avatar, names, bio, counts; children are the buttons. */
export function ProfileHeader({
  handle,
  displayName,
  avatarUrl,
  bio,
  detail,
  counts,
  listsOpen = true,
  children,
}: Props) {
  const { colors } = useTheme();
  return (
    <View style={[styles.header, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={[styles.avatar, { backgroundColor: colors.accent }]}>
        {avatarUrl ? (
          <Image source={{ uri: avatarUrl }} style={StyleSheet.absoluteFill} contentFit="cover" />
        ) : (
          <Text variant="title" style={{ color: colors.onAccent }}>
            {displayName.slice(0, 1).toUpperCase()}
          </Text>
        )}
      </View>
      <Text variant="title">{displayName}</Text>
      <Text muted>@{handle}</Text>
      {bio ? <Text>{bio}</Text> : null}
      {detail ? (
        <Text variant="caption" muted>
          {detail}
        </Text>
      ) : null}
      <View style={styles.counts}>
        <Count label="Posts" value={counts.posts} />
        {listsOpen ? (
          <>
            <Link href={{ pathname: '/u/[handle]/followers', params: { handle } }} asChild>
              <Pressable
                accessibilityRole="link"
                accessibilityLabel={`${counts.followers} followers`}
              >
                <Count label="Followers" value={counts.followers} testID="followers-count" />
              </Pressable>
            </Link>
            <Link href={{ pathname: '/u/[handle]/following', params: { handle } }} asChild>
              <Pressable
                accessibilityRole="link"
                accessibilityLabel={`${counts.following} following`}
              >
                <Count label="Following" value={counts.following} testID="following-count" />
              </Pressable>
            </Link>
          </>
        ) : (
          <>
            <Count label="Followers" value={counts.followers} testID="followers-count" />
            <Count label="Following" value={counts.following} testID="following-count" />
          </>
        )}
      </View>
      {children}
    </View>
  );
}

function Count({ label, value, testID }: { label: string; value: number; testID?: string }) {
  return (
    <View style={styles.count}>
      <Text style={styles.bold} testID={testID}>
        {value}
      </Text>
      <Text variant="caption" muted>
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  header: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.xs },
  avatar: {
    width: 64,
    height: 64,
    borderRadius: radius.pill,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  counts: { flexDirection: 'row', gap: spacing.lg, marginVertical: spacing.sm },
  count: { alignItems: 'center' },
  bold: { fontWeight: '700' },
});
