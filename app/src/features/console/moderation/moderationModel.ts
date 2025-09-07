import { ApiError } from '@/api/http';
import type { ReportItem, ResolveRequestAction } from '@/api/generated/model';

/** The two tabs of the moderation page. */
export type ModerationTab = 'OPEN' | 'RESOLVED';

const REASON_LABELS: Record<string, string> = {
  SPAM: 'Spam',
  ABUSE: 'Abuse',
  NUDITY: 'Nudity',
  VIOLENCE: 'Violence',
  COPYRIGHT: 'Copyright',
  OTHER: 'Other',
};

const KIND_LABELS: Record<ReportItem['kind'], string> = {
  POST: 'Post',
  COMMENT: 'Comment',
  GIG_COMMENT: 'Gig comment',
};

export function kindLabel(kind: ReportItem['kind']): string {
  return KIND_LABELS[kind];
}

/** "Spam ×2", "Copyright": the reasons, most given first (the server sends them in that order). */
export function reasonLabels(item: Pick<ReportItem, 'reasons'>): string[] {
  return Object.entries(item.reasons).map(
    ([reason, count]) => `${REASON_LABELS[reason] ?? reason}${count > 1 ? ` ×${count}` : ''}`,
  );
}

/** The decisions that fit a target: a post can be hidden, a comment deleted, either dismissed. */
export function actionsFor(kind: ReportItem['kind']): ResolveRequestAction[] {
  return kind === 'POST' ? ['HIDE_POST', 'DISMISS'] : ['DELETE_COMMENT', 'DISMISS'];
}

export const ACTION_LABELS: Record<ResolveRequestAction, string> = {
  HIDE_POST: 'Hide post',
  DELETE_COMMENT: 'Delete comment',
  DISMISS: 'Dismiss',
};

/** What a done decision reads as on the Resolved tab. */
export const ACTION_DONE: Record<ResolveRequestAction, string> = {
  HIDE_POST: 'Hidden',
  DELETE_COMMENT: 'Deleted',
  DISMISS: 'Dismissed',
};

function reportsPhrase(count: number): string {
  return count === 1 ? 'Its open report' : `Its ${count} open reports`;
}

/** What the confirmation says will change, one line each. */
export function decisionChanges(item: ReportItem, action: ResolveRequestAction): string[] {
  const reports = reportsPhrase(item.reportCount);
  switch (action) {
    case 'HIDE_POST':
      return [
        'The post disappears from every feed and list, for everyone but its author',
        'Its author sees it marked "Hidden by moderators"',
        `${reports} ${item.reportCount === 1 ? 'is' : 'are'} resolved as actioned`,
        'You can restore it from the Resolved tab',
      ];
    case 'DELETE_COMMENT':
      return [
        'The comment is deleted for everyone (this cannot be undone)',
        `${reports} ${item.reportCount === 1 ? 'is' : 'are'} resolved as actioned`,
      ];
    case 'DISMISS':
      return [
        `The ${item.kind === 'POST' ? 'post' : 'comment'} stays as it is`,
        `${reports} ${item.reportCount === 1 ? 'is' : 'are'} dismissed`,
      ];
  }
}

export const RESTORE_CHANGES: readonly string[] = [
  'The post is published again, back in its place in every feed and list',
  'Its reports stay resolved; the history keeps the decision',
];

/** Whether the Resolved tab offers Restore: a post that is hidden now. */
export function canRestore(item: ReportItem): boolean {
  return item.kind === 'POST' && item.post?.status === 'HIDDEN';
}

/** The message a failed decision or restore shows in its dialog (which stays open to retry or cancel). */
export function problemMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'POST_REPLACED':
        return 'The author has posted again for this gig, so this post cannot come back. The new post stays.';
      case 'REPORT_NOT_OPEN':
        return 'Someone has resolved these reports already. The list is refreshed.';
      case 'ACTION_DOES_NOT_FIT':
        return 'That action does not apply to this.';
      case 'POST_NOT_HIDDEN':
        return 'This post is not hidden any more.';
    }
  }
  return 'That did not work. Try again.';
}
