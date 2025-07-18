import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useCallback } from 'react';

import type { Post } from '@/api/generated/model';
import { getGetPostQueryKey } from '@/api/generated/posts/posts';
import { recordHandoff } from '@/features/player/handoff';
import { usePlayback } from '@/features/player/PlaybackScope';
import { handOverPlayer } from '@/features/player/VideoPlayer';

/**
 * Opens a post from the feed so that its clip plays on: the feed's player of the clip in view is
 * view is reserved for the post page, which starts on that clip, the post page gets the feed's
 * sound setting (and, on the web, the position), and the post page starts from the feed's copy of the
 * post instead of a skeleton, fetching a fresh copy behind it.
 */
export function useOpenPost(post: Post, clipId?: string) {
  const router = useRouter();
  const client = useQueryClient();
  const { muted } = usePlayback();
  return useCallback(() => {
    // Players are per clip; a post without clips plays under its own id.
    const clipKey = clipId ?? post.clips[0]?.id ?? post.id;
    handOverPlayer(clipKey);
    recordHandoff(post.id, muted, clipKey);
    client.setQueryData<Post>(getGetPostQueryKey(post.id), (cached) => cached ?? post, {
      // Stale at once: the post page shows this copy and asks the server for the current one.
      updatedAt: 0,
    });
    router.push({
      pathname: '/post/[id]',
      params: clipKey === post.id ? { id: post.id } : { id: post.id, clip: clipKey },
    });
  }, [client, clipId, muted, post, router]);
}
