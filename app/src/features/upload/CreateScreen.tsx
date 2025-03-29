import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Platform, ScrollView, StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { ComposeForm } from './ComposeForm';
import { compressVideo, discardFile } from './compress';
import { chooseUpload } from './compressDecision';
import { pickFromLibrary, recordWithCamera, type PickResult } from './pickVideo';
import { UploadRow } from './UploadRow';
import { useUploadQueue } from './uploadQueue';
import { removeUpload, resumeProcessing, startUpload, submitPost } from './uploadRunner';
import { problemWith } from './validate';

/**
 * Create tab: pick or record a clip, compress it on Android and start uploading it; then write the
 * caption and choose the gig and artists while it uploads, and Post. The list below shows every upload
 * from this device going through the pipeline.
 */
export function CreateScreen() {
  const { colors } = useTheme();
  const ownerId = useAuthStore((state) => state.me?.id ?? null);
  const all = useUploadQueue((state) => state.entries);
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  // The upload whose caption and tags are being written (it uploads meanwhile).
  const [composing, setComposing] = useState<string | null>(null);

  // Uploads the worker had not finished when the app was last open: ask about them again.
  useEffect(() => {
    if (ownerId) {
      resumeProcessing(ownerId);
    }
  }, [ownerId]);

  if (!ownerId) {
    return null;
  }
  const entries = all.filter((entry) => entry.ownerId === ownerId);

  const add = async (pick: () => Promise<PickResult>) => {
    setProblem(null);
    try {
      const picked = await pick();
      if (picked.kind === 'denied') {
        setProblem('Camera access was not granted. You can allow it in the system settings.');
        return;
      }
      if (picked.kind === 'cancelled') {
        return;
      }
      const refused = problemWith(picked.video);
      if (refused) {
        setProblem(refused);
        return;
      }
      setBusy('Preparing…');
      const compressed = await compressVideo(picked.video, (fraction) =>
        setBusy(`Compressing ${Math.round(fraction * 100)}%`),
      );
      const { video, clientMeta, temporary, discard } = chooseUpload(
        picked.video,
        compressed,
        Platform.OS,
      );
      if (discard) {
        discardFile(discard);
      }
      const localId = useUploadQueue.getState().enqueue(ownerId, video, clientMeta, temporary);
      void startUpload(localId);
      setComposing(localId);
    } catch {
      setProblem('That video could not be opened. Try another one.');
    } finally {
      setBusy(null);
    }
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="title">Share a clip</Text>
        <Text muted>Up to 60 seconds from a gig. It uploads in the background.</Text>
        <View style={styles.buttons}>
          <Button
            title="Choose a video"
            disabled={busy !== null}
            onPress={() => void add(pickFromLibrary)}
            testID="choose-video"
          />
          {Platform.OS !== 'web' ? (
            <Button
              title="Record a clip"
              variant="secondary"
              disabled={busy !== null}
              onPress={() => void add(recordWithCamera)}
            />
          ) : null}
        </View>
        {busy ? (
          <Text muted testID="create-busy">
            {busy}
          </Text>
        ) : null}
        {problem ? (
          <Text style={{ color: colors.danger }} accessibilityRole="alert" testID="create-problem">
            {problem}
          </Text>
        ) : null}

        {composing && entries.some((entry) => entry.localId === composing && !entry.draft) ? (
          <ComposeForm
            onPost={(draft) => {
              void submitPost(composing, draft);
              setComposing(null);
              // Back to the profile, where the new post shows with a "Processing…" badge.
              router.push('/me');
            }}
          />
        ) : null}

        <Text variant="label" muted style={styles.heading}>
          MY UPLOADS
        </Text>
        {entries.length === 0 ? (
          <Text muted testID="uploads-empty">
            Nothing uploaded from this device yet.
          </Text>
        ) : (
          entries.map((entry) => (
            <UploadRow
              key={entry.localId}
              entry={entry}
              onRetry={() => void startUpload(entry.localId)}
              onRemove={() => removeUpload(entry.localId)}
            />
          ))
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.sm, paddingBottom: spacing.xl },
  buttons: { gap: spacing.sm, marginTop: spacing.sm },
  heading: { marginTop: spacing.lg },
});
