import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Platform, ScrollView, StyleSheet, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
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
import {
  pruneFinished,
  removeUpload,
  resumeProcessing,
  startUpload,
  submitPost,
} from './uploadRunner';
import { problemWith } from './validate';

/**
 * Create tab: pick (up to ten) or record clips, compress them on Android and start uploading them;
 * then choose the gig and artists and write the caption while they upload, and Post: each clip becomes
 * a post with the same tags. After posting, "Add more clips from <gig>" starts again with
 * that gig chosen. The list below shows the uploads from this device still on their way.
 */
export function CreateScreen() {
  const { colors } = useTheme();
  const ownerId = useAuthStore((state) => state.me?.id ?? null);
  const all = useUploadQueue((state) => state.entries);
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  // The uploads whose caption and tags are being written (they upload meanwhile).
  const [composing, setComposing] = useState<string[]>([]);
  // The gig of the last post from this screen (offered for more clips), and the gig a form starts on.
  const [lastGig, setLastGig] = useState<GigSummary | null>(null);
  const [formGig, setFormGig] = useState<GigSummary | null>(null);

  // Uploads the worker had not finished when the app was last open: ask about them again.
  useEffect(() => {
    if (ownerId) {
      resumeProcessing(ownerId);
    }
  }, [ownerId]);
  // Each time the tab is opened, uploads that are now playable posts leave the list: the profile is
  // where they live (and where they are deleted from).
  useFocusEffect(
    useCallback(() => {
      if (ownerId) {
        pruneFinished(ownerId);
      }
    }, [ownerId]),
  );

  if (!ownerId) {
    return null;
  }
  const entries = all.filter((entry) => entry.ownerId === ownerId);

  const add = async (pick: () => Promise<PickResult>, gig: GigSummary | null = null) => {
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
      // Clips that cannot be posted (too long, too big) are named; the others go ahead.
      const several = picked.videos.length > 1;
      const refusals: string[] = [];
      const accepted = picked.videos.filter((video) => {
        const refused = problemWith(video);
        if (refused) {
          refusals.push(several ? `${video.fileName ?? 'A clip'}: ${refused}` : refused);
        }
        return !refused;
      });
      if (refusals.length > 0) {
        setProblem(refusals.join('\n'));
      }
      const queued: string[] = [];
      for (const [index, original] of accepted.entries()) {
        const which = accepted.length > 1 ? ` ${index + 1} of ${accepted.length}` : '';
        setBusy(`Preparing${which}…`);
        const compressed = await compressVideo(original, (fraction) =>
          setBusy(`Compressing${which}: ${Math.round(fraction * 100)}%`),
        );
        const { video, clientMeta, temporary, discard } = chooseUpload(
          original,
          compressed,
          Platform.OS,
        );
        if (discard) {
          discardFile(discard);
        }
        const localId = useUploadQueue.getState().enqueue(ownerId, video, clientMeta, temporary);
        void startUpload(localId);
        queued.push(localId);
      }
      if (queued.length > 0) {
        setFormGig(gig);
        setComposing(queued);
      }
    } catch {
      setProblem('That video could not be opened. Try another one.');
    } finally {
      setBusy(null);
    }
  };

  // The uploads the compose form is open for, as long as nothing has been written for them.
  const composingEntries = entries.filter(
    (entry) => composing.includes(entry.localId) && !entry.draft,
  );

  return (
    <Screen keyboard>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text variant="title">Share clips</Text>
        <Text muted>
          Up to 60 seconds each, up to ten at a time from one gig. They upload in the background.
        </Text>
        {lastGig && composingEntries.length === 0 ? (
          <Button
            title={`Add more clips from ${lastGig.title}`}
            variant="secondary"
            disabled={busy !== null}
            onPress={() => void add(pickFromLibrary, lastGig)}
            testID="more-from-gig"
          />
        ) : null}
        <View style={styles.buttons}>
          <Button
            title="Choose videos"
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

        {composingEntries.length > 0 ? (
          <>
            <Text variant="caption" muted testID="compose-for">
              {composingEntries.length === 1
                ? `The post for ${composingEntries[0]!.fileName ?? 'your recorded clip'}`
                : `${composingEntries.length} clips: one post each, same gig and tags`}
            </Text>
            <ComposeForm
              key={composingEntries.map((entry) => entry.localId).join(',')}
              clips={composingEntries.length}
              initialGig={formGig}
              onPost={(draft, gig) => {
                for (const entry of composingEntries) {
                  void submitPost(entry.localId, draft);
                }
                setComposing([]);
                setLastGig(gig);
                // Back to the profile, where the new posts show with a "Processing…" badge.
                router.push('/me');
              }}
            />
          </>
        ) : null}

        <Text variant="label" muted style={styles.heading}>
          ON THEIR WAY
        </Text>
        {entries.length === 0 ? (
          <Text muted testID="uploads-empty">
            Nothing uploading. Posted clips are on your profile.
          </Text>
        ) : (
          entries.map((entry) => (
            <UploadRow
              key={entry.localId}
              entry={entry}
              onRetry={() => void startUpload(entry.localId)}
              onRemove={() => removeUpload(entry.localId)}
              onPostAgain={() => {
                if (entry.draft) {
                  void submitPost(entry.localId, entry.draft);
                }
              }}
              onWritePost={
                composing.includes(entry.localId)
                  ? undefined
                  : () => {
                      setFormGig(null);
                      setComposing([entry.localId]);
                    }
              }
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
