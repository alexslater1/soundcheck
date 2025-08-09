import Ionicons from '@expo/vector-icons/Ionicons';
import { useIsFocused } from '@react-navigation/native';
import { useVideoPlayer, VideoView } from 'expo-video';
import { useEffect } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius } from '@/theme/tokens';

import { LOCAL_FILE_BUFFER } from './localPlayback';
import { usePreviewPlaying } from './previewPlaying';

/**
 * A picked clip played straight from the phone: the compose form shows what is being posted while
 * it is compressed and uploaded in the background. Nothing goes over the network. Tap to play with
 * sound, tap again to pause; playing another preview or leaving the tab pauses it. The web twin is
 * `ClipPreview.web.tsx`.
 */
export function ClipPreview({ uri, testID }: { uri: string; testID?: string }) {
  const { colors } = useTheme();
  const onScreen = useIsFocused();
  // One preview plays at a time: tapping another pauses this one.
  const playing = usePreviewPlaying((state) => state.playing === uri);
  const toggle = usePreviewPlaying((state) => state.toggle);
  const release = usePreviewPlaying((state) => state.release);
  useEffect(() => () => release(uri), [release, uri]);
  const player = useVideoPlayer({ uri }, (created) => {
    created.loop = true;
    created.bufferOptions = LOCAL_FILE_BUFFER;
  });

  useEffect(() => {
    if (playing && onScreen) {
      player.play();
    } else {
      player.pause();
    }
  }, [player, playing, onScreen]);

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={playing ? 'Pause the clip' : 'Play the clip'}
      onPress={() => toggle(uri)}
      style={[styles.frame, { backgroundColor: colors.surfaceRaised }]}
      testID={testID}
    >
      {/* The native view took the tap once it played, so the clip could not be paused: it gets
          no touches, and every tap reaches the Pressable. */}
      <View style={StyleSheet.absoluteFill} pointerEvents="none">
        <VideoView
          player={player}
          style={StyleSheet.absoluteFill}
          contentFit="cover"
          nativeControls={false}
        />
      </View>
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
