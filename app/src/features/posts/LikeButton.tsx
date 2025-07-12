import Ionicons from '@expo/vector-icons/Ionicons';
import { Pressable, StyleSheet } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget } from '@/theme/tokens';

import type { LikeState } from './likeState';
import { useLike } from './useLike';

/**
 * The heart. Optimistic (see {@link useLike}): it flips and the count moves the moment it is pressed,
 * and a double tap on the clip changes the same state.
 */
export function LikeButton({
  postId,
  initial,
  onDark = false,
  stacked = false,
}: {
  postId: string;
  /** What the server last said about this post's likes. */
  initial: LikeState;
  /** True when drawn over the clip: a light icon, whatever the theme. */
  onDark?: boolean;
  /** The count under the heart instead of beside it. */
  stacked?: boolean;
}) {
  const { colors } = useTheme();
  const ink = onDark ? '#FFFFFF' : colors.text;
  const { state, busy, toggle } = useLike(postId, initial);

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
