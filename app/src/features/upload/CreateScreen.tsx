import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueryClient } from '@tanstack/react-query';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';

import {
  getListGigsToPostQueryKey,
  useGetGig,
  useSetGigAttendance,
} from '@/api/generated/catalog/catalog';
import type { GigSummary } from '@/api/generated/model';
import { getListUnpostedGigsQueryKey, useGetPost } from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { reorder, useDragReorder } from '@/components/ui/useDragReorder';
import { useAuthStore } from '@/features/auth/store';
import { isGigList } from '@/features/gigs/AttendanceButtons';
import { dayHasCome } from '@/features/gigs/attendance';
import { formatGigDate } from '@/features/gigs/format';
import { useEffectEvent } from '@/lib/useEffectEvent';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ActPicker } from './ActPicker';
import type { TaggedArtist } from './composeDraft';
import { ClipPreview } from './ClipPreview';
import { ComposeForm, type ComposeResult } from './ComposeForm';
import { billOf } from './composeDraft';
import { GigPicker } from './GigPicker';
import { stateLabel } from './labels';
import { PendingGigs, useMarkPendingGigsSeen } from './PendingGigs';
import { PhotoPreview } from './PhotoPreview';
import { pickFromLibrary, type PickResult } from './pickVideo';
import {
  actsOverLimit,
  festivalDays,
  MAX_CLIPS_PER_ACT,
  MAX_SONG,
  postHint,
} from './postPlan';
import { RecordClipButton } from './RecordClipButton';
import { UploadProblems } from './UploadProblems';
import { UploadRow } from './UploadRow';
import { useUploadQueue } from './uploadQueue';
import {
  makePreview,
  pruneFinished,
  removeUpload,
  resumeProcessing,
  retryPost,
  startUpload,
  submitPost,
} from './uploadRunner';
import { CLIP_LENGTH, isPhoto, problemWith } from './validate';

export { CREATE_ROUTE } from './routes';

/** "Fri 1 Aug" for a festival day chip. */
function dayLabel(day: string): string {
  return formatGigDate(`${day}T12:00:00Z`).split(' ').slice(0, 3).join(' ');
}

/**
 * Create tab: which gig and the clips from it, in either order, the act on stage in each clip and
 * the song (typed, optional), who the user was with and a caption, then Post: the clips become one
 * post. Picked clips are queued at once and play straight from the phone while the queue prepares
 * them (compressing only off Wi-Fi) and uploads them; the post is made at once and each clip fills
 * its place in it as its upload completes. "I was there" marks a gig Went without posting, and the
 * Pending list offers the gigs still without clips. With a `postId` param the clips join that post.
 */
export function CreateScreen() {
  const { colors } = useTheme();
  const ownerId = useAuthStore((state) => state.me?.id ?? null);
  const all = useUploadQueue((state) => state.entries);
  const router = useRouter();
  const client = useQueryClient();
  const params = useLocalSearchParams<{ gigId?: string; postId?: string }>();
  const joinPostId = typeof params.postId === 'string' && params.postId ? params.postId : null;
  const paramGigId =
    !joinPostId && typeof params.gigId === 'string' && params.gigId ? params.gigId : null;
  const joinPost = useGetPost(joinPostId ?? '', { query: { enabled: joinPostId !== null } });
  const paramGig = useGetGig(paramGigId ?? '', undefined, {
    query: { enabled: paramGigId !== null },
  });
  const attend = useSetGigAttendance();

  const [problem, setProblem] = useState<string | null>(null);
  // The gig (null with `gigChosen` = "not from a gig"), and the day of a festival.
  const [gig, setGig] = useState<GigSummary | null>(null);
  const [gigChosen, setGigChosen] = useState(false);
  const [day, setDay] = useState<string | null>(null);
  // The uploads this post is for, the act chosen for each, and the clip whose act is being chosen.
  const [composing, setComposing] = useState<string[]>([]);
  // The picker has handed back control but is still copying the chosen files.
  const [fetching, setFetching] = useState(false);
  const [acts, setActs] = useState<Record<string, TaggedArtist | null>>({});
  const [choosingAct, setChoosingAct] = useState<string | null>(null);
  // The song typed for each clip: free text, optional.
  const [songs, setSongs] = useState<Record<string, string>>({});
  // The gig of the last post from this screen, offered for more clips.
  const [lastGig, setLastGig] = useState<GigSummary | null>(null);
  const [marked, setMarked] = useState<string | null>(null);

  // Opening the tab clears the pending gigs from its badge.
  useMarkPendingGigsSeen(ownerId);

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
  // A gig picked on Me's Pending list arrives as a param: it becomes this post's gig, once.
  const applied = useRef<string | null>(null);
  useEffect(() => {
    const picked = paramGig.data?.gig;
    if (picked && paramGigId && applied.current !== paramGigId) {
      applied.current = paramGigId;
      setGig(picked);
      setGigChosen(true);
      setDay(null);
      setMarked(null);
    }
  }, [paramGig.data, paramGigId]);

  // The clips shown under "Your clips", in the order the fan put them; a held clip drags along.
  const shownIds = composing.filter((localId) =>
    all.some((entry) => entry.localId === localId && entry.ownerId === ownerId && !entry.draft),
  );
  const drag = useDragReorder({
    count: shownIds.length,
    horizontal: true,
    onMove: (from, to) => {
      const moved = reorder(shownIds, from, to);
      setComposing((current) => [...moved, ...current.filter((id) => !moved.includes(id))]);
    },
  });

  if (!ownerId) {
    return null;
  }
  const entries = all.filter((entry) => entry.ownerId === ownerId);
  // In the order the fan put them: a dragged clip keeps its new place in the post.
  const composingEntries = composing
    .map((localId) => entries.find((entry) => entry.localId === localId))
    .filter((entry): entry is (typeof entries)[number] => entry !== undefined && !entry.draft);
  const joining = joinPostId !== null;
  const postGig = joining ? (joinPost.data?.gig ?? null) : gig;
  const bill = postGig ? billOf(postGig) : [];
  const actOf = (localId: string): TaggedArtist | null =>
    localId in acts ? (acts[localId] ?? null) : (bill[0] ?? null);
  const clipActs = composingEntries.map((entry) => ({
    localId: entry.localId,
    artistId: actOf(entry.localId)?.id ?? null,
    song: songs[entry.localId] ?? null,
  }));
  const overLimit = actsOverLimit(clipActs).length > 0;
  const hint = postHint({
    gigChosen,
    clips: composingEntries.length,
    overLimit,
    joining,
  });
  const days = !joining && gig ? festivalDays(gig, new Date()) : [];

  function chooseGig(next: GigSummary | null) {
    setGig(next);
    setGigChosen(next !== null);
    setDay(null);
    setMarked(null);
  }

  const add = async (pick: () => Promise<PickResult>) => {
    setProblem(null);
    // Not on the web: a cancelled file dialog may never answer there.
    setFetching(Platform.OS !== 'web');
    try {
      const picked = await pick();
      setFetching(false);
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
      const queued: { localId: string; photo: boolean }[] = [];
      for (const video of picked.videos) {
        const refused = problemWith(video);
        if (refused) {
          refusals.push(several ? `${video.fileName ?? 'A clip'}: ${refused}` : refused);
          continue;
        }
        // Queued unprepared: the queue decides about compressing, in the background. A photo goes
        // up as it is, prepared, and needs no preview frame: it is ready once uploaded.
        const photo = isPhoto(video);
        const localId = useUploadQueue
          .getState()
          .enqueue(ownerId, video, { platform: Platform.OS }, false, photo);
        queued.push({ localId, photo });
      }
      if (refusals.length > 0) {
        setProblem(refusals.join('\n'));
      }
      // Every clip shows under "Your clips" first; the uploads and preview frames start after.
      setComposing((current) => [...current, ...queued.map((q) => q.localId)]);
      await nextTurn();
      for (const { localId, photo } of queued) {
        void startUpload(localId);
        if (!photo) {
          // The frame shown blurred while the clip is on its way; never waited for.
          void makePreview(localId);
        }
      }
    } catch {
      setProblem('That file could not be opened. Try another one.');
    } finally {
      setFetching(false);
    }
  };

  const wasThere = async () => {
    if (!gig) {
      return;
    }
    setMarked(null);
    try {
      const saved = await attend.mutateAsync({ id: gig.id, data: { status: 'WENT' } });
      setGig({ ...gig, myAttendance: saved.myAttendance, wentUnconfirmed: false });
      setMarked('Marked: you went. It is in your Pending list until you post clips from it.');
      void client.invalidateQueries({
        predicate: (query) =>
          isGigList(query.queryKey) ||
          query.queryKey[0] === getListUnpostedGigsQueryKey()[0] ||
          query.queryKey[0] === getListGigsToPostQueryKey()[0],
      });
    } catch {
      setMarked('That did not work. Try again.');
    }
  };

  const post = ({ caption, taggedUserIds }: ComposeResult) => {
    // The poster's order, as shown under "Your clips": no longer sorted by the bill.
    const clips = clipActs;
    void submitPost({
      caption,
      gigId: joining ? null : (gig?.id ?? null),
      day,
      taggedUserIds,
      joinPostId,
      clips,
    });
    setComposing([]);
    setActs({});
    setSongs({});
    setChoosingAct(null);
    if (joinPostId) {
      router.setParams({ postId: undefined, gigId: undefined });
      router.push({ pathname: '/post/[id]', params: { id: joinPostId } });
      return;
    }
    setLastGig(gig);
    chooseGig(null);
    // Back to the profile, where the new post shows with its clips on their way.
    router.push('/me');
  };

  return (
    <Screen keyboard>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text variant="title">{joining ? 'Add clips' : 'Share clips'}</Text>
        <UploadProblems ownerId={ownerId} />
        {!joining && lastGig && composingEntries.length === 0 && !gigChosen ? (
          <Button
            title={`Add more clips from ${lastGig.title}`}
            variant="secondary"
            onPress={() => chooseGig(lastGig)}
            testID="more-from-gig"
          />
        ) : null}

        {joining ? (
          <Step title="The post" done={joinPost.data !== undefined}>
            {joinPost.isPending ? (
              <Text muted>Loading the post…</Text>
            ) : joinPost.isError ? (
              <StateMessage
                title="Could not load the post"
                onRetry={() => void joinPost.refetch()}
                testID="join-error"
              />
            ) : (
              <View style={styles.row}>
                <Text style={styles.grow} testID="joining">
                  {joinPost.data.author.id === ownerId
                    ? 'Adding to your post'
                    : `Adding to @${joinPost.data.author.handle}'s post`}
                  {joinPost.data.gig ? ` from ${joinPost.data.gig.title}` : ''}
                </Text>
                <Button
                  title="Cancel"
                  variant="secondary"
                  size="small"
                  onPress={() => router.setParams({ postId: undefined, gigId: undefined })}
                />
              </View>
            )}
          </Step>
        ) : (
          <Step title="Which gig?" done={gigChosen}>
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
            {gig && days.length > 1 ? (
              <View style={styles.box} testID="festival-days">
                <Text variant="label" muted>
                  WHICH DAY?
                </Text>
                <View style={styles.wrap}>
                  {days.map((d, i) => (
                    <Chip
                      key={d}
                      label={`Day ${i + 1} · ${dayLabel(d)}`}
                      selected={(day ?? days[0]) === d}
                      onPress={() => setDay(i === 0 ? null : d)}
                    />
                  ))}
                </View>
              </View>
            ) : null}
            {gig && dayHasCome(gig.startsAt) && gig.status !== 'CANCELLED' ? (
              // Only once the gig's day has come; an assumed Went (a past Going) asks again.
              gig.myAttendance === 'WENT' && !gig.wentUnconfirmed && !marked ? (
                <Text variant="caption" muted testID="went-already">
                  You went. No clips? It stays in your Pending list.
                </Text>
              ) : marked ? (
                <Text variant="caption" muted testID="was-there-done">
                  {marked}
                </Text>
              ) : (
                <Button
                  title="I was there (no clips)"
                  variant="secondary"
                  size="small"
                  disabled={attend.isPending}
                  onPress={() => void wasThere()}
                  testID="was-there"
                />
              )
            ) : null}
          </Step>
        )}

        <Step title="Your clips" done={composingEntries.length > 0}>
          <Text variant="caption" muted>
            Videos up to {CLIP_LENGTH} each, photos up to 10 MB, up to {MAX_CLIPS_PER_ACT} of each
            act. They upload while you write.
          </Text>
          {fetching ? (
            // The system picker copies every chosen file before it hands them over.
            <View style={styles.fetching} testID="clips-fetching">
              <ActivityIndicator color={colors.accent} />
              <Text muted>Getting your clips…</Text>
            </View>
          ) : null}
          {composingEntries.length > 0 ? (
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              scrollEnabled={!drag.active}
              testID="clip-previews"
            >
              {/* Hold a clip to lift it, drag it along, let go: the post keeps that order. */}
              <View style={styles.previews} {...drag.containerProps} testID="clip-previews-row">
                {composingEntries.map((entry, index) => {
                  const act = actOf(entry.localId);
                  const move = (to: number) => {
                    if (to >= 0 && to < composingEntries.length) {
                      const moved = reorder(shownIds, index, to);
                      setComposing((current) => [
                        ...moved,
                        ...current.filter((id) => !moved.includes(id)),
                      ]);
                    }
                  };
                  return (
                    <Animated.View key={entry.localId} {...drag.itemProps(index)}>
                      <Pressable
                        // Not a control of its own: the long press lifts the clip; its parts stay reachable.
                        accessible={false}
                        {...drag.liftProps(index)}
                        style={styles.preview}
                        testID={`tile-${entry.localId}`}
                      >
                        {isPhoto(entry) ? (
                          <PhotoPreview
                            uri={entry.previewUri}
                            testID={`preview-${entry.localId}`}
                          />
                        ) : (
                          <ClipPreview
                            uri={entry.previewUri}
                            testID={`preview-${entry.localId}`}
                            hold={drag.liftProps(index)}
                          />
                        )}
                        <View style={styles.previewFoot}>
                          <Text
                            variant="label"
                            muted
                            numberOfLines={1}
                            style={styles.grow}
                            // Dragging needs a finger: a screen reader moves the clip with these instead.
                            accessibilityLabel={`${stateLabel(entry)}. Clip ${index + 1} of ${composingEntries.length}`}
                            accessibilityActions={[
                              { name: 'moveUp', label: 'Move up' },
                              { name: 'moveDown', label: 'Move down' },
                            ]}
                            onAccessibilityAction={(event) =>
                              move(
                                event.nativeEvent.actionName === 'moveUp' ? index - 1 : index + 1,
                              )
                            }
                            testID={`order-${entry.localId}`}
                          >
                            {stateLabel(entry)}
                          </Text>
                          <Pressable
                            accessibilityRole="button"
                            accessibilityLabel={`Remove ${entry.fileName ?? 'this clip'}`}
                            hitSlop={8}
                            onPress={() => {
                              setComposing((current) =>
                                current.filter((id) => id !== entry.localId),
                              );
                              void removeUpload(entry.localId).catch(() => undefined);
                            }}
                          >
                            <Ionicons name="close-circle" size={20} color={colors.textMuted} />
                          </Pressable>
                        </View>
                        <Pressable
                          accessibilityRole="button"
                          accessibilityLabel={`Act in ${entry.fileName ?? 'this clip'}: ${act?.name ?? 'no act'}`}
                          onPress={() =>
                            setChoosingAct((now) => (now === entry.localId ? null : entry.localId))
                          }
                          style={[
                            styles.actChip,
                            {
                              borderColor:
                                choosingAct === entry.localId ? colors.accent : colors.border,
                            },
                          ]}
                          testID={`act-${entry.localId}`}
                        >
                          <Text variant="caption" numberOfLines={1} style={styles.grow}>
                            {act?.name ?? 'No act'}
                          </Text>
                          <Ionicons name="chevron-down" size={14} color={colors.textMuted} />
                        </Pressable>
                        <TextInput
                          value={songs[entry.localId] ?? ''}
                          onChangeText={(song) =>
                            setSongs((now) => ({ ...now, [entry.localId]: song }))
                          }
                          placeholder="Song (optional)"
                          placeholderTextColor={colors.textMuted}
                          maxLength={MAX_SONG}
                          accessibilityLabel={`Song in ${entry.fileName ?? 'this clip'}`}
                          style={[
                            styles.songInput,
                            { color: colors.text, borderColor: colors.border },
                          ]}
                          testID={`song-${entry.localId}`}
                        />
                      </Pressable>
                    </Animated.View>
                  );
                })}
              </View>
            </ScrollView>
          ) : null}
          {choosingAct && composing.includes(choosingAct) ? (
            <ActPicker
              bill={bill}
              value={actOf(choosingAct)}
              onChange={(act) => {
                setActs((now) => ({ ...now, [choosingAct]: act }));
                setChoosingAct(null);
              }}
            />
          ) : null}
          {overLimit ? (
            <Text style={{ color: colors.danger }} testID="act-limit">
              A post can hold {MAX_CLIPS_PER_ACT} of your clips of one act. Remove some, or give
              them another act.
            </Text>
          ) : null}
          <View style={styles.buttons}>
            <Button
              title={composingEntries.length > 0 ? 'Add more' : 'Choose videos or photos'}
              variant={composingEntries.length > 0 ? 'secondary' : 'primary'}
              onPress={() => void add(pickFromLibrary)}
              testID="choose-video"
            />
            {Platform.OS !== 'web' ? (
              <RecordClipButton disabled={false} onPick={(pick) => void add(pick)} />
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

        <Step title={joining ? 'Add them' : 'Caption and post'} done={hint === null}>
          <ComposeForm
            key={joinPostId ?? 'new'}
            meId={ownerId}
            clips={composingEntries.length}
            hint={hint}
            joining={joining}
            onPost={post}
          />
        </Step>

        {/* Below the post being made: a way to start the next one. */}
        {!joining && !gigChosen ? <PendingGigs onPick={chooseGig} /> : null}

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
              onPostAgain={() => void retryPost(entry.localId)}
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

/** One part of the Create tab; a tick once it is done. Parts can be done in any order. */
function Step({
  title,
  done,
  children,
}: {
  title: string;
  done: boolean;
  children: React.ReactNode;
}) {
  const { colors } = useTheme();
  return (
    <View
      style={[styles.step, { borderColor: colors.border, backgroundColor: colors.surface }]}
      testID={`step-${title}`}
    >
      <View style={styles.stepHead}>
        <View
          style={[styles.badge, { backgroundColor: done ? colors.accent : colors.surfaceRaised }]}
          testID={`step-${title}-${done ? 'done' : 'todo'}`}
        >
          {done ? (
            <Ionicons name="checkmark" size={14} color={colors.onAccent} />
          ) : (
            <Ionicons name="ellipse-outline" size={12} color={colors.textMuted} />
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

/** Lets React draw what was just set before slower work starts. */
function nextTurn(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

const styles = StyleSheet.create({
  fetching: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  content: { gap: spacing.md, paddingBottom: spacing.xl },
  step: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  stepHead: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  badge: {
    width: 22,
    height: 22,
    borderRadius: 11,
    alignItems: 'center',
    justifyContent: 'center',
  },
  box: { gap: spacing.xs },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  grow: { flex: 1 },
  buttons: { gap: spacing.sm },
  previews: { flexDirection: 'row', gap: spacing.sm },
  preview: { width: 120, gap: spacing.xs },
  previewFoot: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  actChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    borderWidth: 1,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.xs,
    paddingVertical: 2,
  },
  songInput: {
    borderWidth: 1,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.xs,
    paddingVertical: 2,
    fontSize: 12,
  },
  heading: { marginTop: spacing.md },
});
