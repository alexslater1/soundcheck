import { ticketDate } from './ticketDate';

describe('ticketDate', () => {
  it('gives the month, day and year of the gig in UK time', () => {
    expect(ticketDate('2026-09-26T19:30:00Z')).toEqual({ month: 'SEP', day: '26', year: '2026' });
  });

  it('uses the evening’s UK date for a gig that starts after midnight UTC would say', () => {
    // 23:30 in London on 31 Dec 2025 (UTC+0) is still 2025; 00:30 BST on 1 June is the 1st.
    expect(ticketDate('2025-12-31T23:30:00Z')).toEqual({ month: 'DEC', day: '31', year: '2025' });
    expect(ticketDate('2026-05-31T23:30:00Z')).toEqual({ month: 'JUN', day: '1', year: '2026' });
  });
});
