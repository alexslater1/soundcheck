import { useVideoPlayer, VideoView } from 'expo-video';
import { useState } from 'react';
import { Modal, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { discardFile } from './compress';
import { megabytes } from './labels';
import { LOCAL_FILE_BUFFER } from './localPlayback';
import { recordWithCamera, type PickResult } from './pickVideo';
import type { PickedVideo } from './types';
import { problemWith } from './validate';

/** "2:05" for 125 s; null when the length is unknown. */
export function clipLength(durationMs: number | null): string | null {
  if (durationMs === null) {
    return null;
  }
  const seconds = Math.round(durationMs / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

type Props = {
  disabled: boolean;
  /** Hands the result over as the Create tab handles any pick (checks, queueing, problems). */
  onPick: (pick: () => Promise<PickResult>) => void;
};

/**
 * "Record a clip": the system camera records, then the clip plays here before it is used.
 * The camera app's own confirm screen cannot play what was just recorded, so a fan could post a clip
 * without knowing whether the sound worked. Retake deletes the take and opens the camera again; Use
 * hands it to the Create tab like a picked clip; Discard (or the back button) deletes it. A take lives
 * in the app's cache, not the gallery, so a deleted take is gone.
 */
export function RecordClipButton({ disabled, onPick }: Props) {
  const [take, setTake] = useState<PickedVideo | null>(null);

  const record = async () => {
    let result: PickResult;
    try {
      result = await recordWithCamera();
    } catch (error) {
      onPick(() => Promise.reject(error));
      return;
    }
    const [video] = result.kind === 'picked' ? result.videos : [];
    if (video) {
      setTake(video);
    } else {
      onPick(async () => result);
    }
  };
  const drop = (video: PickedVideo) => {
    discardFile(video.uri);
    setTake(null);
  };

  return (
    <>
      <Button
        title="Record a clip"
        variant="secondary"
        disabled={disabled}
        onPress={() => void record()}
        testID="record-clip"
      />
      {take ? (
        <ClipReview
          video={take}
          onUse={() => {
            setTake(null);
            onPick(async () => ({ kind: 'picked', videos: [take] }));
          }}
          onRetake={() => {
            drop(take);
            void record();
          }}
          onDiscard={() => drop(take)}
        />
      ) : null}
    </>
  );
}

/**
 * The just-recorded clip, full screen, with the player's own controls (play, pause and the seek bar
 * to scrub through it), what it is (length, size) and whether it can be posted.
 */
export function ClipReview({
  video,
  onUse,
  onRetake,
  onDiscard,
}: {
  video: PickedVideo;
  onUse: () => void;
  onRetake: () => void;
  onDiscard: () => void;
}) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const player = useVideoPlayer({ uri: video.uri }, (created) => {
    created.loop = false;
    created.bufferOptions = LOCAL_FILE_BUFFER;
    created.play();
  });
  const problem = problemWith(video);
  const facts = [clipLength(video.durationMs), megabytes(video.bytes)].filter(Boolean).join(' · ');

  return (
    <Modal visible animationType="slide" onRequestClose={onDiscard} testID="clip-review">
      <View
        style={[
          styles.screen,
          { paddingTop: insets.top + spacing.sm, paddingBottom: insets.bottom + spacing.md },
        ]}
      >
        <View style={styles.head}>
          <Text variant="title" style={styles.light}>
            Your clip
          </Text>
          <Text variant="caption" style={styles.light} testID="clip-review-facts">
            {facts}
          </Text>
        </View>
        <VideoView
          player={player}
          style={styles.video}
          contentFit="contain"
          nativeControls
          testID="clip-review-video"
        />
        {problem ? (
          <Text
            style={{ color: colors.danger }}
            accessibilityRole="alert"
            testID="clip-review-problem"
          >
            {problem}
          </Text>
        ) : null}
        <View style={styles.actions}>
          <Button
            title="Retake"
            variant="secondary"
            onPress={onRetake}
            testID="clip-review-retake"
          />
          <Button
            title="Use clip"
            disabled={problem !== null}
            onPress={onUse}
            testID="clip-review-use"
          />
        </View>
        <Text
          variant="caption"
          style={[styles.light, styles.discard]}
          accessibilityRole="button"
          onPress={onDiscard}
          testID="clip-review-discard"
        >
          Discard this take
        </Text>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  // Always dark, whatever the theme: the clip is the thing to look at.
  screen: { flex: 1, backgroundColor: '#000000', paddingHorizontal: spacing.md, gap: spacing.md },
  head: { gap: spacing.xs },
  light: { color: '#FFFFFF' },
  video: { flex: 1 },
  actions: { flexDirection: 'row', gap: spacing.sm, justifyContent: 'center' },
  discard: { textAlign: 'center', opacity: 0.8 },
});
