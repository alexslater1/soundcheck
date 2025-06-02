import Ionicons from '@expo/vector-icons/Ionicons';
import { useIsFocused } from '@react-navigation/native';
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius } from '@/theme/tokens';

const FILL: CSSProperties = {
  position: 'absolute',
  inset: 0,
  width: '100%',
  height: '100%',
  objectFit: 'cover',
};

/** A picked clip played from its `blob:` URL in the browser (the native twin is `ClipPreview.tsx`). */
export function ClipPreview({ uri, testID }: { uri: string; testID?: string }) {
  const { colors } = useTheme();
  const onScreen = useIsFocused();
  const [playing, setPlaying] = useState(false);
  const video = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    const element = video.current;
    if (!element) {
      return;
    }
    if (playing && onScreen) {
      element.play().catch(() => setPlaying(false));
    } else {
      element.pause();
    }
  }, [playing, onScreen]);

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={playing ? 'Pause the clip' : 'Play the clip'}
      onPress={() => setPlaying((p) => !p)}
      style={[styles.frame, { backgroundColor: colors.surfaceRaised }]}
      testID={testID}
    >
      <video ref={video} src={uri} style={FILL} loop playsInline preload="metadata" />
      {playing ? null : (
        <View style={styles.play}>
          <Ionicons name="play" size={28} color="#FFFFFF" />
        </View>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  frame: { width: '100%', aspectRatio: 9 / 16, borderRadius: radius.md, overflow: 'hidden' },
  play: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.25)',
  },
});
