import Ionicons from '@expo/vector-icons/Ionicons';
import { useState } from 'react';
import { Pressable, StyleSheet } from 'react-native';

import { likePost, unlikePost } from '@/api/generated/engagement/engagement';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget } from '@/theme/tokens';

import { toggled, type LikeState } from './likeState';

/**
 * The heart. Optimistic: it flips and the count moves the moment it is pressed; the server's answer
 * (the real count) replaces the guess, and a failure puts back what was there before.
 */
export function LikeButton({ postId, initial }: { postId: string; initial: LikeState }) {
  const { colors } = useTheme();
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
    } catch {
      setState(before);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Pressable
      style={styles.button}
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
        color={state.likedByMe ? colors.accent : colors.text}
      />
      <Text testID="like-count">{state.likeCount}</Text>
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
});
