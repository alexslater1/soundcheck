import { ApiError } from '@/api/http';
import type { ReportItem } from '@/api/generated/model';

import {
  actionsFor,
  canRestore,
  decisionChanges,
  problemMessage,
  reasonLabels,
} from './moderationModel';

const item = (over: Partial<ReportItem>): ReportItem => ({
  kind: 'POST',
  id: 'p1',
  reportId: 'r1',
  reportCount: 3,
  reasons: { SPAM: 2, COPYRIGHT: 1 },
  latestDetails: [],
  firstReportedAt: '2026-10-04T10:00:00Z',
  lastReportedAt: '2026-10-04T12:00:00Z',
  ...over,
});

describe('moderationModel', () => {
  it('labels the reasons with how many gave each', () => {
    expect(reasonLabels(item({}))).toEqual(['Spam ×2', 'Copyright']);
  });

  it('offers hide for posts and delete for comments, dismiss for both', () => {
    expect(actionsFor('POST')).toEqual(['HIDE_POST', 'DISMISS']);
    expect(actionsFor('COMMENT')).toEqual(['DELETE_COMMENT', 'DISMISS']);
    expect(actionsFor('GIG_COMMENT')).toEqual(['DELETE_COMMENT', 'DISMISS']);
  });

  it('says what each decision will change', () => {
    expect(decisionChanges(item({}), 'HIDE_POST')).toEqual([
      'The post disappears from every feed and list, for everyone but its author',
      'Its author sees it marked "Hidden by moderators"',
      'Its 3 open reports are resolved as actioned',
      'You can restore it from the Resolved tab',
    ]);
    expect(decisionChanges(item({ kind: 'COMMENT', reportCount: 1 }), 'DISMISS')).toEqual([
      'The comment stays as it is',
      'Its open report is dismissed',
    ]);
    expect(decisionChanges(item({ kind: 'GIG_COMMENT' }), 'DELETE_COMMENT')[0]).toBe(
      'The comment is deleted for everyone (this cannot be undone)',
    );
  });

  it('offers Restore only for a post that is hidden now', () => {
    const post = (status: 'HIDDEN' | 'PUBLISHED') =>
      item({ post: { id: 'p1', status, clipCount: 1 } });
    expect(canRestore(post('HIDDEN'))).toBe(true);
    expect(canRestore(post('PUBLISHED'))).toBe(false);
    expect(canRestore(item({ kind: 'COMMENT' }))).toBe(false);
  });

  it('turns the server’s refusals into sentences', () => {
    expect(problemMessage(new ApiError(409, 'POST_REPLACED', 'x'))).toMatch(
      /author has posted again for this gig/,
    );
    expect(problemMessage(new ApiError(409, 'REPORT_NOT_OPEN', 'x'))).toMatch(/resolved/);
    expect(problemMessage(new Error('offline'))).toBe('That did not work. Try again.');
  });
});
