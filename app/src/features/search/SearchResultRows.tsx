import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import { Link } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';

import type { PersonResult, Suggestion } from '@/api/generated/model';
import { Avatar } from '@/components/ui/Avatar';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

const PICTURE = 44;

/** A person: picture, name, handle; a lock when the profile is private. Opens their profile. */
export function PersonRow({ person }: { person: PersonResult }) {
  const { colors } = useTheme();
  const { user } = person;
  const isPrivate = person.profileVisibility === 'PRIVATE';
  return (
    <Link href={{ pathname: '/u/[handle]', params: { handle: user.handle } }} asChild>
      <Pressable
        accessibilityRole="link"
        accessibilityLabel={`${user.displayName}, @${user.handle}${isPrivate ? ', private profile' : ''}`}
        style={styles.row}
        testID="search-person"
      >
        <Avatar uri={user.avatarUrl} name={user.displayName} size={PICTURE} />
        <View style={styles.text}>
          <Text style={styles.bold} numberOfLines={1}>
            {user.displayName}
          </Text>
          <View style={styles.line}>
            <Text variant="caption" muted numberOfLines={1}>
              @{user.handle}
            </Text>
            {isPrivate ? (
              <View style={styles.line} testID="search-person-private">
                <Ionicons name="lock-closed" size={12} color={colors.textMuted} />
                <Text variant="caption" muted>
                  Private
                </Text>
              </View>
            ) : null}
          </View>
        </View>
        {user.isFollowing ? (
          <Text variant="caption" muted>
            Following
          </Text>
        ) : null}
      </Pressable>
    </Link>
  );
}

/**
 * An artist or a venue from the suggestions: picture (or an icon), name, and the venue's town or the
 * artist's upcoming gigs. Opens the feed of clips tagged with it.
 */
export function PlaceOrActRow({
  suggestion,
  onOpen,
}: {
  suggestion: Suggestion;
  onOpen: (suggestion: Suggestion) => void;
}) {
  const { colors } = useTheme();
  const artist = suggestion.kind === 'ARTIST';
  const detail = artist
    ? suggestion.upcomingGigs > 0
      ? `${suggestion.upcomingGigs} upcoming`
      : 'Artist'
    : (suggestion.city ?? 'Venue');
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${artist ? 'Artist' : 'Venue'}: ${suggestion.name}, see clips`}
      onPress={() => onOpen(suggestion)}
      style={styles.row}
      testID={artist ? 'search-artist' : 'search-venue'}
    >
      <View
        style={[
          styles.picture,
          artist ? styles.round : null,
          { backgroundColor: colors.accent2Soft },
        ]}
      >
        {suggestion.imageUrl ? (
          <Image
            source={{ uri: suggestion.imageUrl }}
            style={StyleSheet.absoluteFill}
            contentFit="cover"
          />
        ) : (
          <Ionicons
            name={artist ? 'mic-outline' : 'location-outline'}
            size={20}
            color={colors.text}
          />
        )}
      </View>
      <View style={styles.text}>
        <Text style={styles.bold} numberOfLines={1}>
          {suggestion.name}
        </Text>
        <Text variant="caption" muted numberOfLines={1}>
          {detail}
        </Text>
      </View>
      <Ionicons name="play-circle-outline" size={22} color={colors.textMuted} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.xs,
  },
  text: { flex: 1, minWidth: 0 },
  line: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  bold: { fontWeight: '700' },
  picture: {
    width: PICTURE,
    height: PICTURE,
    borderRadius: radius.sm,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  round: { borderRadius: PICTURE / 2 },
});
