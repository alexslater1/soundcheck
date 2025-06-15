import { timesSeenText } from './TimesSeen';

describe('timesSeenText', () => {
  it('says once, n times, or n+ times when there are more than one page', () => {
    expect(timesSeenText('Keo', 1, false)).toBe('You’ve seen Keo once');
    expect(timesSeenText('Keo', 5, false)).toBe('You’ve seen Keo 5 times');
    expect(timesSeenText('Keo', 50, true)).toBe('You’ve seen Keo 50+ times');
  });
});
