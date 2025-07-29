import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import { Link } from 'expo-router';
import type { PropsWithChildren, ReactNode } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

type Props = PropsWithChildren<{
  handle: string;
  displayName: string;
  avatarUrl?: string | null;
  bio?: string | null;
  /** The line under the bio: the home city (and, on one's own, the genres, each a link). */
  detail?: ReactNode;
  counts: { posts: number; followers: number; following: number; artists: number };
  /** False for a private profile the viewer may not see: the counts show, the lists do not open. */
  listsOpen?: boolean;
  /** One's own profile: tapping the photo changes it. */
  onEditPhoto?: () => void;
  /** The new photo is being saved. */
  photoBusy?: boolean;
  /** One's own profile: tapping the bio (or "Add a bio") edits it. */
  onEditBio?: () => void;
  /** Top-right corner, e.g. the Settings gear on Me. */
  corner?: ReactNode;
  /** Tapping the Artists count opens the Artists tab. */
  onArtistsPress?: () => void;
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
  onEditPhoto,
  photoBusy = false,
  onEditBio,
  corner,
  onArtistsPress,
  children,
}: Props) {
  const { colors } = useTheme();
  const avatar = (
    <View style={[styles.avatar, { backgroundColor: colors.accent }]}>
      {avatarUrl ? (
        <Image source={{ uri: avatarUrl }} style={StyleSheet.absoluteFill} contentFit="cover" />
      ) : (
        <Text variant="title" style={{ color: colors.onAccent }}>
          {displayName.slice(0, 1).toUpperCase()}
        </Text>
      )}
      {photoBusy ? (
        <View style={[StyleSheet.absoluteFill, styles.busy]}>
          <ActivityIndicator color={colors.onAccent} />
        </View>
      ) : null}
    </View>
  );
  return (
    <View style={[styles.header, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      {corner ? <View style={styles.corner}>{corner}</View> : null}
      {onEditPhoto ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={avatarUrl ? 'Change profile photo' : 'Add a profile photo'}
          disabled={photoBusy}
          onPress={onEditPhoto}
          style={styles.avatarButton}
          testID="profile-photo"
        >
          {avatar}
          <View
            style={[styles.badge, { backgroundColor: colors.accent2, borderColor: colors.surface }]}
          >
            <Ionicons name="camera" size={12} color={colors.background} />
          </View>
        </Pressable>
      ) : (
        avatar
      )}
      <Text variant="title">{displayName}</Text>
      <Text muted>@{handle}</Text>
      {onEditBio ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={bio ? `Bio: ${bio}. Edit` : 'Add a bio'}
          onPress={onEditBio}
          testID="profile-bio"
        >
          {bio ? <Text>{bio}</Text> : <Text muted>Add a bio</Text>}
        </Pressable>
      ) : bio ? (
        <Text>{bio}</Text>
      ) : null}
      {typeof detail === 'string' ? (
        <Text variant="caption" muted>
          {detail}
        </Text>
      ) : (
        (detail ?? null)
      )}
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
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`${counts.artists} artists followed`}
              onPress={onArtistsPress}
              disabled={!onArtistsPress}
            >
              <Count label="Artists" value={counts.artists} testID="artists-count" />
            </Pressable>
          </>
        ) : (
          <>
            <Count label="Followers" value={counts.followers} testID="followers-count" />
            <Count label="Following" value={counts.following} testID="following-count" />
            <Count label="Artists" value={counts.artists} testID="artists-count" />
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
  corner: { position: 'absolute', top: spacing.sm, right: spacing.sm, zIndex: 1 },
  avatarButton: { alignSelf: 'flex-start' },
  busy: { alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.45)' },
  badge: {
    position: 'absolute',
    right: -2,
    bottom: -2,
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
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
