import { useQueryClient } from '@tanstack/react-query';
import { View } from 'react-native';

import {
  addGigComment,
  deleteGigComment,
  getListGigCommentsQueryKey,
  useListGigComments,
} from '@/api/generated/engagement/engagement';
import { useAuthStore } from '@/features/auth/store';
import { CommentComposer, CommentThread } from '@/features/posts/Comments';
import { spacing } from '@/theme/tokens';

import { dayHasCome } from './attendance';

/**
 * The comment thread at the foot of a gig page, the post comments' list and box reused.
 * Before the gig's day it asks "Who's going?", from its day on "How was it?". Anyone can comment; only
 * the writer can delete their comment; nobody is notified.
 */
export function GigComments({ gigId, startsAt }: { gigId: string; startsAt: string }) {
  const client = useQueryClient();
  const myId = useAuthStore((state) => state.me?.id);
  const comments = useListGigComments(gigId, { limit: 50 });
  const refresh = () => client.invalidateQueries({ queryKey: getListGigCommentsQueryKey(gigId) });
  // The gig's UK day has come: the same rule as the rest of the gig page.
  const happened = dayHasCome(startsAt);
  return (
    <View style={{ gap: spacing.sm, marginTop: spacing.md }} testID="gig-comments">
      <CommentThread
        title={happened ? 'How was it?' : "Who's going?"}
        comments={comments}
        canDelete={(comment) => comment.author?.id === myId}
        onDelete={async (commentId) => {
          await deleteGigComment(commentId);
          await refresh();
        }}
        empty={happened ? 'No comments yet. Were you there?' : 'No comments yet. Going?'}
      />
      <CommentComposer
        pinned={false}
        placeholder={happened ? 'Say how it was' : 'Say something about this gig'}
        onSend={async (text, key) => {
          await addGigComment(gigId, { body: text }, { headers: { 'Idempotency-Key': key } });
          await refresh();
        }}
      />
    </View>
  );
}
