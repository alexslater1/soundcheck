import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { useMediaStatus } from './useMediaStatus';
import { VideoPlayer } from './VideoPlayer';

/**
 * One clip on its own page: plays it when it is READY, and otherwise says where it is in the pipeline
 * (the owner sees every status; anyone else gets 404 until it is READY).
 */
export function ClipScreen({ id }: { id: string }) {
  const { colors } = useTheme();
  const media = useMediaStatus(id);

  if (media.isPending) {
    return (
      <Screen>
        <View style={styles.center} testID="clip-loading">
          <Skeleton height={420} />
        </View>
      </Screen>
    );
  }
  if (!media.data) {
    return (
      <Screen>
        <StateMessage
          testID="clip-error"
          title="Could not load this clip"
          detail="It may have been removed, or it is not ready to be shared yet."
          onRetry={() => void media.refetch()}
        />
      </Screen>
    );
  }

  const clip = media.data;
  if (clip.status === 'READY' && clip.hlsUrl) {
    const aspectRatio = clip.width && clip.height ? clip.width / clip.height : undefined;
    return (
      <Screen>
        <View style={[styles.center, aspectRatio && aspectRatio < 1 ? styles.portrait : null]}>
          <VideoPlayer
            testID="clip-player"
            hlsUrl={clip.hlsUrl}
            posterUrl={clip.posterUrl}
            blurhash={clip.blurhash}
            aspectRatio={aspectRatio}
            active
          />
        </View>
      </Screen>
    );
  }
  if (clip.status === 'REJECTED' || clip.status === 'FAILED') {
    return (
      <Screen>
        <StateMessage
          testID="clip-failed"
          title={clip.status === 'REJECTED' ? 'This file could not be used' : 'Processing failed'}
          detail={
            clip.status === 'REJECTED'
              ? 'It is not a video we can play. Try another clip (up to 60 seconds).'
              : 'Something went wrong on our side while preparing this clip.'
          }
        />
      </Screen>
    );
  }
  return (
    <Screen>
      <View style={styles.processing} testID="clip-processing">
        <ActivityIndicator color={colors.accent} />
        <Text variant="title">Processing…</Text>
        <Text muted>
          {media.isFetching || media.fetchStatus === 'idle'
            ? 'Your clip is being prepared for playback. This page updates by itself.'
            : 'Your clip is being prepared for playback.'}
        </Text>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, justifyContent: 'center' },
  // A portrait clip would be taller than the window on a wide screen: cap its width instead.
  portrait: { maxWidth: 360, width: '100%', alignSelf: 'center' },
  processing: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: spacing.sm },
});
