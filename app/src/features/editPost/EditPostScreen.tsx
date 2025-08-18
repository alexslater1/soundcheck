import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueryClient } from '@tanstack/react-query';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Animated, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import type { Post, UserSummary } from '@/api/generated/model';
import { editPost, useGetPost } from '@/api/generated/posts/posts';
import { useListFollowing } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { reorder, useDragReorder } from '@/components/ui/useDragReorder';
import { useAuthStore } from '@/features/auth/store';
import { ActPicker } from '@/features/upload/ActPicker';
import { MAX_SONG } from '@/features/upload/postPlan';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { actChoices, canRemove, changes, type EditState, initialState, moveClip } from './editPlan';
import { refreshPosts } from './refreshPosts';

/** People a post can tag. */
const MAX_TAGGED = 10;

/**
 * ⋯ → Edit: the caption, the clips' order, acts and songs, removing clips (never the last one), and
 * the people tagged. Adding clips goes through Create, which uploads them into this post.
 */
export function EditPostScreen({ id }: { id: string }) {
  const query = useGetPost(id);
  const myId = useAuthStore((state) => state.me?.id ?? null);
  if (query.isPending) {
    return (
      <Screen>
        <View style={styles.form} testID="edit-loading">
          <Skeleton height={44} />
          <Skeleton height={120} />
          <Skeleton height={44} />
        </View>
      </Screen>
    );
  }
  if (query.isError || !query.data) {
    return (
      <Screen>
        <StateMessage
          title="Could not load this post"
          detail="Check your connection and try again."
          onRetry={() => void query.refetch()}
          testID="edit-error"
        />
      </Screen>
    );
  }
  if (query.data.author.id !== myId) {
    return (
      <Screen>
        <StateMessage title="Only its author can edit this post" testID="edit-not-yours" />
      </Screen>
    );
  }
  return <EditForm post={query.data} myId={myId} />;
}

function EditForm({ post, myId }: { post: Post; myId: string }) {
  const { colors } = useTheme();
  const router = useRouter();
  const client = useQueryClient();
  const [before] = useState<EditState>(() => initialState(post));
  const [state, setState] = useState<EditState>(before);
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  // The clip whose act is being picked (any act, the bill's first).
  const [picking, setPicking] = useState<string | null>(null);
  const following = useListFollowing(myId, { limit: 50 });
  const choices = actChoices(post, state.clips);
  // Hold a clip and drag it to its place; Move up / Move down stay for screen readers.
  const drag = useDragReorder({
    count: state.clips.length,
    onMove: (from, to) => setState((s) => ({ ...s, clips: reorder(s.clips, from, to) })),
  });

  // The people offered: whoever is tagged now, then the people the author follows.
  const people: UserSummary[] = [...state.tagged];
  for (const user of following.data?.items ?? []) {
    if (!people.some((p) => p.id === user.id)) {
      people.push(user);
    }
  }
  const isTagged = (user: UserSummary) => state.tagged.some((t) => t.id === user.id);
  const toggle = (user: UserSummary) =>
    setState((s) => ({
      ...s,
      tagged: isTagged(user)
        ? s.tagged.filter((t) => t.id !== user.id)
        : s.tagged.length < MAX_TAGGED
          ? [...s.tagged, user]
          : s.tagged,
    }));

  const save = async () => {
    setSaving(true);
    setProblem(null);
    try {
      const body = changes(before, state);
      if (Object.keys(body).length > 0) {
        await editPost(post.id, body);
      }
      await refreshPosts(client);
      router.back();
    } catch (error) {
      setProblem(
        error instanceof Error && error.message ? error.message : 'Could not save. Try again.',
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <Screen keyboard>
      <ScrollView
        contentContainerStyle={styles.form}
        keyboardShouldPersistTaps="handled"
        scrollEnabled={!drag.active}
      >
        <Text variant="label" muted>
          CAPTION
        </Text>
        <TextInput
          value={state.caption}
          onChangeText={(caption) => setState((s) => ({ ...s, caption }))}
          placeholder="Say something about it"
          placeholderTextColor={colors.textMuted}
          accessibilityLabel="Caption"
          multiline
          maxLength={2200}
          style={[styles.input, { color: colors.text, borderColor: colors.border }]}
        />

        <Text variant="label" muted>
          CLIPS
        </Text>
        <Text variant="caption" muted>
          Tap an act to change it, and name the song if you like. The clips play in this order: hold
          one to drag it to another place.
        </Text>
        <View style={styles.clips} {...drag.containerProps} testID="edit-clips">
          {state.clips.map((clip, index) => (
            <Animated.View key={clip.id} {...drag.itemProps(index)} testID={`edit-slot-${clip.id}`}>
              <Pressable
                // Not a control of its own: the long press lifts the clip; its parts stay reachable.
                accessible={false}
                {...drag.liftProps(index)}
                style={[
                  styles.clip,
                  { borderColor: colors.border, backgroundColor: colors.surface },
                ]}
                testID={`edit-clip-${clip.id}`}
              >
                <View style={[styles.thumb, { backgroundColor: colors.surfaceRaised }]}>
                  {clip.thumbUrl ? (
                    <Image
                      source={{ uri: clip.thumbUrl }}
                      style={StyleSheet.absoluteFill}
                      contentFit="cover"
                    />
                  ) : null}
                </View>
                <View style={styles.grow}>
                  <Chip
                    // A clip with no act is on no artist's page: ask, when the gig
                    // has acts to pick.
                    label={clip.act?.name ?? (choices.length > 0 ? 'Which act is this?' : 'No act')}
                    selected={clip.act !== null}
                    onPress={() => setPicking((open) => (open === clip.id ? null : clip.id))}
                  />
                  {picking === clip.id ? (
                    // An act from outside the bill joins the gig's bill once the post is saved.
                    <ActPicker
                      bill={choices}
                      value={clip.act}
                      onChange={(act) => {
                        setState((s) => ({
                          ...s,
                          clips: s.clips.map((c) => (c.id === clip.id ? { ...c, act } : c)),
                        }));
                        setPicking(null);
                      }}
                    />
                  ) : null}
                  <TextInput
                    value={clip.song}
                    onChangeText={(song) =>
                      setState((s) => ({
                        ...s,
                        clips: s.clips.map((c) => (c.id === clip.id ? { ...c, song } : c)),
                      }))
                    }
                    placeholder="Song (optional)"
                    placeholderTextColor={colors.textMuted}
                    maxLength={MAX_SONG}
                    accessibilityLabel="Song in this clip"
                    style={[styles.song, { color: colors.text, borderColor: colors.border }]}
                    testID={`edit-song-${clip.id}`}
                  />
                  {clip.owner.id !== myId ? (
                    <Text variant="caption" muted>
                      Filmed by @{clip.owner.handle}
                    </Text>
                  ) : null}
                </View>
                <IconAction
                  icon="chevron-up"
                  label="Move up"
                  disabled={index === 0}
                  onPress={() => setState((s) => ({ ...s, clips: moveClip(s.clips, index, -1) }))}
                />
                <IconAction
                  icon="chevron-down"
                  label="Move down"
                  disabled={index === state.clips.length - 1}
                  onPress={() => setState((s) => ({ ...s, clips: moveClip(s.clips, index, 1) }))}
                />
                <IconAction
                  icon="close-circle"
                  label="Remove this clip"
                  disabled={!canRemove(state.clips)}
                  onPress={() =>
                    setState((s) => ({ ...s, clips: s.clips.filter((c) => c.id !== clip.id) }))
                  }
                />
              </Pressable>
            </Animated.View>
          ))}
        </View>
        {!canRemove(state.clips) ? (
          <Text variant="caption" muted>
            A post keeps at least one clip. To remove this one, delete the post.
          </Text>
        ) : null}
        {post.gig ? (
          <Button
            title="Add clips"
            variant="secondary"
            onPress={() =>
              router.push({ pathname: '/create', params: { postId: post.id, gigId: post.gig!.id } })
            }
            testID="edit-add-clips"
          />
        ) : null}

        <Text variant="label" muted>
          WHO WERE YOU WITH?
        </Text>
        {people.length === 0 ? (
          <Text variant="caption" muted testID="edit-no-people">
            {following.isPending ? 'Loading the people you follow…' : 'Follow someone to tag them.'}
          </Text>
        ) : (
          <View style={styles.chips}>
            {people.map((user) => (
              <Chip
                key={user.id}
                label={`@${user.handle}`}
                selected={isTagged(user)}
                disabled={!isTagged(user) && state.tagged.length >= MAX_TAGGED}
                onPress={() => toggle(user)}
              />
            ))}
          </View>
        )}

        {problem ? (
          <Text style={{ color: colors.danger }} accessibilityRole="alert" testID="edit-problem">
            {problem}
          </Text>
        ) : null}
        <Button
          title={saving ? 'Saving…' : 'Save'}
          disabled={saving}
          onPress={() => void save()}
          testID="edit-save"
        />
      </ScrollView>
    </Screen>
  );
}

function IconAction({
  icon,
  label,
  disabled,
  onPress,
}: {
  icon: React.ComponentProps<typeof Ionicons>['name'];
  label: string;
  disabled: boolean;
  onPress: () => void;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      disabled={disabled}
      hitSlop={6}
      onPress={onPress}
      style={disabled ? styles.dim : null}
    >
      <Ionicons name={icon} size={22} color={colors.textMuted} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  song: {
    alignSelf: 'stretch',
    borderWidth: 1,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
  },
  form: { gap: spacing.sm, paddingBottom: spacing.xl },
  input: {
    minHeight: 72,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.sm,
    textAlignVertical: 'top',
  },
  clips: { gap: spacing.sm },
  clip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.sm,
  },
  thumb: { width: 48, height: 64, borderRadius: radius.sm, overflow: 'hidden' },
  grow: { flex: 1, gap: spacing.xs, alignItems: 'flex-start' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  dim: { opacity: 0.35 },
});
