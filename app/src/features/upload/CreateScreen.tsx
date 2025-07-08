import Ionicons from '@expo/vector-icons/Ionicons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Platform, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ClipPreview } from './ClipPreview';
import { ComposeForm } from './ComposeForm';
import { GigPicker } from './GigPicker';
import { stateLabel } from './labels';
import { pickFromLibrary, recordWithCamera, type PickResult } from './pickVideo';
import { UploadProblems } from './UploadProblems';
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
 * Create tab, in the order a fan thinks about it: 1. which gig, 2. the clips from it,
 * 3. who is in them and a caption, then Post (one post per clip). Picked clips are queued at once and
 * play straight from the phone while the queue prepares them (compressing only off Wi-Fi)
 * and uploads them, so nothing is waited for before writing. After posting, "Add more clips from
 * <gig>" starts again on the same gig. The list at the bottom shows this device's uploads still on
 * their way.
 */
export function CreateScreen() {
  const { colors } = useTheme();
  const ownerId = useAuthStore((state) => state.me?.id ?? null);
  const all = useUploadQueue((state) => state.entries);
  const router = useRouter();
  const [problem, setProblem] = useState<string | null>(null);
  // Step 1: the gig (null with `gigChosen` = "not from a gig").
  const [gig, setGig] = useState<GigSummary | null>(null);
  const [gigChosen, setGigChosen] = useState(false);
  // Step 2: the uploads this post is for.
  const [composing, setComposing] = useState<string[]>([]);
  // The gig of the last post from this screen, offered for more clips.
  const [lastGig, setLastGig] = useState<GigSummary | null>(null);

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
  const composingEntries = entries.filter(
    (entry) => composing.includes(entry.localId) && !entry.draft,
  );

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
      // Clips that cannot be posted (too long, too big) are named; the others go ahead.
      const several = picked.videos.length > 1;
      const refusals: string[] = [];
      const queued: string[] = [];
      for (const video of picked.videos) {
        const refused = problemWith(video);
        if (refused) {
          refusals.push(several ? `${video.fileName ?? 'A clip'}: ${refused}` : refused);
          continue;
        }
        // Queued unprepared: the queue decides about compressing, in the background (G20).
        const localId = useUploadQueue
          .getState()
          .enqueue(ownerId, video, { platform: Platform.OS }, false, false);
        void startUpload(localId);
        queued.push(localId);
      }
      if (refusals.length > 0) {
        setProblem(refusals.join('\n'));
      }
      setComposing((current) => [...current, ...queued]);
    } catch {
      setProblem('That video could not be opened. Try another one.');
    }
  };

  const chooseGig = (next: GigSummary | null) => {
    setGig(next);
    setGigChosen(next !== null);
  };

  return (
    <Screen keyboard>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text variant="title">Share clips</Text>
        <UploadProblems ownerId={ownerId} />
        {lastGig && composingEntries.length === 0 && !gigChosen ? (
          <Button
            title={`Add more clips from ${lastGig.title}`}
            variant="secondary"
            onPress={() => {
              setGig(lastGig);
              setGigChosen(true);
            }}
            testID="more-from-gig"
          />
        ) : null}

        <Step number={1} title="Which gig?" done={gigChosen}>
          {gigChosen && gig === null ? (
            <View style={styles.row}>
              <Text muted style={styles.grow}>
                Not from a gig. Nobody will find these clips on a gig&apos;s page.
              </Text>
              <Button
                title="Choose a gig"
                variant="secondary"
                onPress={() => setGigChosen(false)}
              />
            </View>
          ) : (
            <>
              <GigPicker value={gig} onChange={chooseGig} />
              {gig === null ? (
                <Text
                  variant="caption"
                  muted
                  accessibilityRole="button"
                  onPress={() => setGigChosen(true)}
                  testID="no-gig"
                >
                  These clips are not from a gig →
                </Text>
              ) : null}
            </>
          )}
        </Step>

        <Step
          number={2}
          title="Your clips"
          done={composingEntries.length > 0}
          disabled={!gigChosen}
        >
          <Text variant="caption" muted>
            Up to 60 seconds each, up to ten at a time. They upload while you write.
          </Text>
          {composingEntries.length > 0 ? (
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.previews}
              testID="clip-previews"
            >
              {composingEntries.map((entry) => (
                <View key={entry.localId} style={styles.preview}>
                  <ClipPreview uri={entry.previewUri} testID={`preview-${entry.localId}`} />
                  <View style={styles.previewFoot}>
                    <Text variant="label" muted numberOfLines={1} style={styles.grow}>
                      {stateLabel(entry)}
                    </Text>
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`Remove ${entry.fileName ?? 'this clip'}`}
                      hitSlop={8}
                      onPress={() => {
                        setComposing((current) => current.filter((id) => id !== entry.localId));
                        void removeUpload(entry.localId).catch(() => undefined);
                      }}
                    >
                      <Ionicons name="close-circle" size={20} color={colors.textMuted} />
                    </Pressable>
                  </View>
                </View>
              ))}
            </ScrollView>
          ) : null}
          <View style={styles.buttons}>
            <Button
              title={composingEntries.length > 0 ? 'Add more videos' : 'Choose videos'}
              variant={composingEntries.length > 0 ? 'secondary' : 'primary'}
              disabled={!gigChosen}
              onPress={() => void add(pickFromLibrary)}
              testID="choose-video"
            />
            {Platform.OS !== 'web' ? (
              <Button
                title="Record a clip"
                variant="secondary"
                disabled={!gigChosen}
                onPress={() => void add(recordWithCamera)}
              />
            ) : null}
          </View>
          {problem ? (
            <Text
              style={{ color: colors.danger }}
              accessibilityRole="alert"
              testID="create-problem"
            >
              {problem}
            </Text>
          ) : null}
        </Step>

        {composingEntries.length > 0 ? (
          <Step number={3} title="Tags and caption" done={false}>
            <ComposeForm
              key={composingEntries[0]!.localId}
              clips={composingEntries.length}
              gig={gig}
              onPost={(draft) => {
                for (const entry of composingEntries) {
                  void submitPost(entry.localId, draft);
                }
                setComposing([]);
                setLastGig(gig);
                setGig(null);
                setGigChosen(false);
                // Back to the profile, where the new posts show with a "Processing…" badge.
                router.push('/me');
              }}
            />
          </Step>
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
                  : () => setComposing((current) => [...current, entry.localId])
              }
            />
          ))
        )}
      </ScrollView>
    </Screen>
  );
}

/** One numbered step of the Create tab; a later step is dimmed until the one before is done. */
function Step({
  number,
  title,
  done,
  disabled = false,
  children,
}: {
  number: number;
  title: string;
  done: boolean;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  const { colors } = useTheme();
  return (
    <View
      style={[
        styles.step,
        { borderColor: colors.border, backgroundColor: colors.surface },
        disabled ? styles.dim : null,
      ]}
      testID={`step-${number}`}
      aria-disabled={disabled}
    >
      <View style={styles.stepHead}>
        <View
          style={[styles.badge, { backgroundColor: done ? colors.accent : colors.surfaceRaised }]}
        >
          {done ? (
            <Ionicons name="checkmark" size={14} color={colors.onAccent} />
          ) : (
            <Text variant="label" style={{ color: colors.text }}>
              {number}
            </Text>
          )}
        </View>
        <Text variant="label" muted>
          {title.toUpperCase()}
        </Text>
      </View>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.md, paddingBottom: spacing.xl },
  step: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  dim: { opacity: 0.5 },
  stepHead: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  badge: {
    width: 22,
    height: 22,
    borderRadius: 11,
    alignItems: 'center',
    justifyContent: 'center',
  },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  grow: { flex: 1 },
  buttons: { gap: spacing.sm },
  previews: { gap: spacing.sm },
  preview: { width: 120, gap: spacing.xs },
  previewFoot: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  heading: { marginTop: spacing.md },
});
