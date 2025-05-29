import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import type { PropsWithChildren } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { usePlayerPrefs } from './playerPrefs';

type Props = PropsWithChildren<{
  posterUrl?: string | null;
  blurhash?: string | null;
  aspectRatio: number;
  /** The poster covers the video until its first frame is on screen. */
  showPoster: boolean;
  /** Hides the sound button (a poster-only clip has no sound to toggle). */
  showSound: boolean;
  failed?: boolean;
  onRetry?: () => void;
  testID?: string;
}>;

/**
 * What every player shares, native or web: the box with the clip's shape, the poster over a BlurHash
 * placeholder (so there is colour before the first byte of the poster arrives), the sound button and
 * the error state. Tapping anywhere on the clip toggles sound.
 */
export function PlayerFrame({
  children,
  posterUrl,
  blurhash,
  aspectRatio,
  showPoster,
  showSound,
  failed = false,
  onRetry,
  testID,
}: Props) {
  const { colors } = useTheme();
  const muted = usePlayerPrefs((state) => state.muted);
  const toggleMuted = usePlayerPrefs((state) => state.toggleMuted);

  return (
    <View style={[styles.frame, { aspectRatio, backgroundColor: colors.surface }]} testID={testID}>
      {children}
      {showPoster ? (
        <Image
          testID={testID ? `${testID}-poster` : undefined}
          source={posterUrl ? { uri: posterUrl } : null}
          placeholder={blurhash ? { blurhash } : null}
          contentFit="cover"
          transition={150}
          style={StyleSheet.absoluteFill}
          accessibilityIgnoresInvertColors
        />
      ) : null}
      {failed ? (
        <View
          style={[StyleSheet.absoluteFill, styles.failed]}
          testID={testID ? `${testID}-error` : undefined}
        >
          <Text style={styles.onVideo}>This clip could not be played.</Text>
          {onRetry ? <Button title="Try again" variant="secondary" onPress={onRetry} /> : null}
        </View>
      ) : showSound ? (
        <Pressable
          style={StyleSheet.absoluteFill}
          onPress={toggleMuted}
          accessibilityRole="button"
          accessibilityLabel={muted ? 'Turn sound on' : 'Turn sound off'}
          testID={testID ? `${testID}-sound` : undefined}
        >
          <View style={styles.soundBadge}>
            <Ionicons name={muted ? 'volume-mute' : 'volume-high'} size={20} color="#FFFFFF" />
          </View>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  frame: { width: '100%', borderRadius: radius.md, overflow: 'hidden' },
  failed: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
    padding: spacing.md,
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  onVideo: { color: '#FFFFFF' },
  soundBadge: {
    position: 'absolute',
    right: spacing.sm,
    bottom: spacing.sm,
    width: touchTarget,
    height: touchTarget,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
});
