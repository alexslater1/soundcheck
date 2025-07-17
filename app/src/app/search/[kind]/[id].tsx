import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { Pressable } from 'react-native';

import { Text } from '@/components/ui/Text';
import { PlaybackScope } from '@/features/player/PlaybackScope';
import { TaggedPostsFeed } from '@/features/search/TaggedPostsFeed';
import { spacing } from '@/theme/tokens';

/** The clips of an artist or a venue picked in the Search tab; the header links to its page. */
export default function TaggedPostsRoute() {
  const router = useRouter();
  const params = useLocalSearchParams<{ kind: string; id: string; name?: string }>();
  const kind = params.kind === 'venue' ? 'venue' : 'artist';
  const name = params.name ?? (kind === 'artist' ? 'Artist' : 'Venue');
  return (
    <PlaybackScope>
      <Stack.Screen
        options={{
          title: name,
          headerRight: () => (
            <Pressable
              accessibilityRole="link"
              accessibilityLabel={kind === 'artist' ? 'Artist page' : 'Venue page'}
              hitSlop={spacing.sm}
              onPress={() =>
                router.push({
                  pathname: kind === 'artist' ? '/artist/[id]' : '/venue/[id]',
                  params: { id: params.id },
                })
              }
              testID="tagged-page-link"
            >
              <Text variant="caption" style={{ fontWeight: '700' }}>
                {kind === 'artist' ? 'Artist page ›' : 'Venue page ›'}
              </Text>
            </Pressable>
          ),
        }}
      />
      <TaggedPostsFeed kind={kind} id={params.id} name={name} />
    </PlaybackScope>
  );
}
