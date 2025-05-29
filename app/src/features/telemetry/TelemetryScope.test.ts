import { eventFor } from './TelemetryScope';

describe('eventFor', () => {
  const now = new Date('2026-10-02T12:00:00Z');

  it('carries the feed session and position of the place the event happened in', () => {
    expect(
      eventFor(
        { surface: 'FOR_YOU', sessionId: 'session-1', position: 4 },
        'LIKE',
        'post-1',
        undefined,
        now,
      ),
    ).toEqual({
      type: 'LIKE',
      postId: 'post-1',
      surface: 'FOR_YOU',
      sessionId: 'session-1',
      position: 4,
      value: null,
      clientTs: '2026-10-02T12:00:00.000Z',
    });
  });

  it('sends the watch fraction as the value, and nulls where there is no session', () => {
    expect(eventFor({ surface: 'POST' }, 'WATCH_PROGRESS', 'post-1', 0.5, now)).toMatchObject({
      type: 'WATCH_PROGRESS',
      surface: 'POST',
      sessionId: null,
      position: null,
      value: 0.5,
    });
  });
});
