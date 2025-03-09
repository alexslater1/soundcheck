/** Display helpers for gigs: pure, so they are unit-tested without rendering anything. */

/** "Fri 2 Oct, 19:30" in the device's own time zone. */
export function formatGigTime(iso: string, timeZone?: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).format(new Date(iso));
}

const SYMBOLS: Record<string, string> = { GBP: '£', EUR: '€', USD: '$' };

function money(amount: number, currency: string): string {
  const symbol = SYMBOLS[currency];
  const digits = Number.isInteger(amount) ? amount.toFixed(0) : amount.toFixed(2);
  return symbol ? `${symbol}${digits}` : `${digits} ${currency}`;
}

/** "£12.50", "£12.50 – £20" or null when the source gave no price. */
export function formatPrice(
  min: number | null | undefined,
  max: number | null | undefined,
  currency: string | null | undefined,
): string | null {
  const code = currency ?? 'GBP';
  const low = min ?? max;
  const high = max ?? min;
  if (low == null || high == null) return null;
  return low === high ? money(low, code) : `${money(low, code)} – ${money(high, code)}`;
}

/** "0.7 km", "12 km"; null when the list had no origin. */
export function formatDistance(km: number | null | undefined): string | null {
  if (km == null) return null;
  return km < 10 ? `${km.toFixed(1)} km` : `${Math.round(km)} km`;
}

/** Only the unusual states are labelled; a scheduled gig needs no badge. */
export function statusLabel(status: string): string | null {
  switch (status) {
    case 'CANCELLED':
      return 'Cancelled';
    case 'POSTPONED':
      return 'Postponed';
    case 'RESCHEDULED':
      return 'Rescheduled';
    default:
      return null;
  }
}

/** The ticket link names where it goes (attribution). */
export function ticketLabel(sources: readonly { source: string }[]): string {
  if (sources.some((s) => s.source === 'TICKETMASTER')) return 'Tickets on Ticketmaster';
  if (sources.some((s) => s.source === 'SKIDDLE')) return 'Tickets on Skiddle';
  return 'Tickets';
}
