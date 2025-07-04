import {
  dateParts,
  formatGigDate,
  formatDistance,
  formatGigTime,
  formatPrice,
  statusLabel,
  ticketLabel,
} from './format';

describe('gig formatting', () => {
  it('formats the start in the given zone', () => {
    // 18:30 UTC is 19:30 in London during BST and 18:30 in winter.
    expect(formatGigTime('2026-10-02T18:30:00Z', 'Europe/London')).toBe('Fri 2 Oct, 19:30');
    expect(formatGigTime('2026-12-04T18:30:00Z', 'Europe/London')).toBe('Fri 4 Dec, 18:30');
  });

  it('formats prices as a single value, a range, or nothing', () => {
    expect(formatPrice(12.5, 12.5, 'GBP')).toBe('£12.50');
    expect(formatPrice(12.5, 20, 'GBP')).toBe('£12.50 – £20');
    expect(formatPrice(15, null, 'GBP')).toBe('£15');
    expect(formatPrice(null, 30, null)).toBe('£30');
    expect(formatPrice(10, 12, 'SEK')).toBe('10 SEK – 12 SEK');
    expect(formatPrice(null, null, 'GBP')).toBeNull();
  });

  it('formats distances with one decimal under 10 km', () => {
    expect(formatDistance(0.7)).toBe('0.7 km');
    expect(formatDistance(9.96)).toBe('10.0 km');
    expect(formatDistance(12.4)).toBe('12 km');
    expect(formatDistance(null)).toBeNull();
  });

  it('labels only unusual statuses and names the ticket source', () => {
    expect(statusLabel('SCHEDULED')).toBeNull();
    expect(statusLabel('CANCELLED')).toBe('Cancelled');
    expect(statusLabel('UNLISTED')).toBe('No longer listed');
    expect(statusLabel('POSTPONED')).toBe('Postponed');
    expect(ticketLabel([{ source: 'TICKETMASTER' }])).toBe('Tickets on Ticketmaster');
    expect(ticketLabel([{ source: 'SKIDDLE' }])).toBe('Tickets on Skiddle');
    expect(ticketLabel([])).toBe('Tickets');
  });
});

describe('dateParts and formatGigDate', () => {
  it('splits a start into a date tile on the UK clock', () => {
    expect(dateParts('2026-10-17T18:30:00Z')).toEqual({
      weekday: 'SAT',
      day: '17',
      month: 'OCT',
      time: '19:30',
    });
    // 00:30 UK on the 18th, not the 17th.
    expect(dateParts('2026-10-17T23:30:00Z').day).toBe('18');
  });

  it('names the night of a concert with its year', () => {
    expect(formatGigDate('2026-10-17T18:30:00Z')).toBe('Sat 17 Oct 2026');
  });
});
