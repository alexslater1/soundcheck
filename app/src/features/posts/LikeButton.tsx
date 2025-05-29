import Ionicons from '@expo/vector-icons/Ionicons';
import { useState } from 'react';
import { Pressable, StyleSheet } from 'react-native';

import { likePost, unlikePost } from '@/api/generated/engagement/engagement';
import { Text } from '@/components/ui/Text';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget } from '@/theme/tokens';

import { toggled, type LikeState } from './likeState';

/**
 * The heart. Optimistic: it flips and the count moves the moment it is pressed; the server's answer
 * (the real count) replaces the guess, and a failure puts back what was there before.
 */
export function LikeButton({
  postId,
  initial,
  onDark = false,
  stacked = false,
}: {
  postId: string;
  initial: LikeState;
  /** True when drawn over the clip: a light icon, whatever the theme. */
  onDark?: boolean;
  /** The count under the heart instead of beside it. */
  stacked?: boolean;
}) {
  const { colors } = useTheme();
  const track = useTrack();
  const ink = onDark ? '#FFFFFF' : colors.text;
  const [state, setState] = useState(initial);
  const [busy, setBusy] = useState(false);

  async function toggle() {
    const before = state;
    const guess = toggled(before);
    setState(guess);
    setBusy(true);
    try {
      const confirmed = await (guess.likedByMe ? likePost(postId) : unlikePost(postId));
      setState({ likedByMe: confirmed.likedByMe, likeCount: confirmed.likeCount });
      // Reported once the server has it: a like that failed is not a signal.
      track(guess.likedByMe ? 'LIKE' : 'UNLIKE', postId);
    } catch {
      setState(before);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Pressable
      style={stacked ? styles.stacked : styles.button}
      disabled={busy}
      onPress={() => void toggle()}
      accessibilityRole="button"
      accessibilityLabel={state.likedByMe ? 'Unlike' : 'Like'}
      accessibilityState={{ selected: state.likedByMe }}
      testID="like-button"
    >
      <Ionicons
        name={state.likedByMe ? 'heart' : 'heart-outline'}
        size={24}
        color={state.likedByMe ? colors.accent : ink}
      />
      <Text testID="like-count" style={{ color: ink }}>
        {state.likeCount}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: touchTarget,
    minWidth: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  stacked: {
    minHeight: touchTarget,
    minWidth: touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
  },
});
