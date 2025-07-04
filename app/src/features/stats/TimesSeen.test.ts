import { timesSeenText } from './TimesSeen';

describe('timesSeenText', () => {
  it('says once or n times, counting every gig (no more "50+")', () => {
    expect(timesSeenText('Keo', 1)).toBe('You’ve seen Keo once');
    expect(timesSeenText('Keo', 5)).toBe('You’ve seen Keo 5 times');
    expect(timesSeenText('Keo', 73)).toBe('You’ve seen Keo 73 times');
  });
});
