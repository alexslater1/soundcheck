import { ImpactFeedbackStyle, impactAsync } from 'expo-haptics';
import { useCallback } from 'react';
import { Platform } from 'react-native';
import { create } from 'zustand';

import { likePost, unlikePost } from '@/api/generated/engagement/engagement';
import { useAuthStore } from '@/features/auth/store';
import { useTrack } from '@/features/telemetry/TelemetryScope';

import { toggled, type LikeState } from './likeState';

type Entry = {
  /** The server's numbers this entry started from: newer numbers from the server replace it. */
  base: LikeState;
  value: LikeState;
  busy: boolean;
};

const same = (a: LikeState, b: LikeState) =>
  a.likedByMe === b.likedByMe && a.likeCount === b.likeCount;

/**
 * What the viewer did to a post's like since its numbers came from the server, by viewer and post. The
 * heart and a double tap on the clip share it, and so do the feed and the post page: a
 * like on one shows on the other without a reload. Keyed by viewer as well, so whoever signs in next
 * on the device never sees the last person's hearts.
 */
export const useLikes = create<{ entries: Record<string, Entry> }>(() => ({ entries: {} }));

const keyOf = (viewerId: string | undefined, postId: string) => `${viewerId ?? ''}/${postId}`;

function setEntry(key: string, entry: Entry) {
  useLikes.setState((state) => ({ entries: { ...state.entries, [key]: entry } }));
}

/** The entry for a post if it still applies to what the server last said. */
function current(key: string, server: LikeState): Entry | undefined {
  const entry = useLikes.getState().entries[key];
  return entry && same(entry.base, server) ? entry : undefined;
}

/** A light tap under the thumb; not on the web. */
function buzz() {
  if (Platform.OS !== 'web') {
    void impactAsync(ImpactFeedbackStyle.Light).catch(() => undefined);
  }
}

/**
 * A post's like, optimistic: it flips and the count moves the moment it is asked; the server's answer
 * (the real count) replaces the guess, and a failure puts back what was there before. `like()` only
 * ever likes (a double tap on a liked clip leaves it liked).
 */
export function useLike(postId: string, server: LikeState) {
  const track = useTrack();
  const viewerId = useAuthStore((state) => state.me?.id);
  const key = keyOf(viewerId, postId);
  const entry = useLikes((state) => state.entries[key]);
  const applies = entry !== undefined && same(entry.base, server);
  const state = applies ? entry.value : server;
  const busy = applies ? entry.busy : false;
  const { likedByMe, likeCount } = server;

  const toggle = useCallback(async () => {
    const base = { likedByMe, likeCount };
    const now = current(key, base);
    if (now?.busy) {
      return;
    }
    const before = now?.value ?? base;
    const guess = toggled(before);
    setEntry(key, { base, value: guess, busy: true });
    if (guess.likedByMe) {
      buzz();
    }
    try {
      const confirmed = await (guess.likedByMe ? likePost(postId) : unlikePost(postId));
      setEntry(key, {
        base,
        value: { likedByMe: confirmed.likedByMe, likeCount: confirmed.likeCount },
        busy: false,
      });
      // Reported once the server has it: a like that failed is not a signal.
      track(guess.likedByMe ? 'LIKE' : 'UNLIKE', postId);
    } catch {
      setEntry(key, { base, value: before, busy: false });
    }
  }, [key, likedByMe, likeCount, postId, track]);

  const like = useCallback(async () => {
    const base = { likedByMe, likeCount };
    const now = current(key, base);
    if ((now?.value ?? base).likedByMe) {
      buzz();
      return;
    }
    await toggle();
  }, [key, likedByMe, likeCount, toggle]);

  return { state, busy, toggle, like };
}
