/**
 * Where the phone is, as the app is allowed to use it: only to measure distances to gigs,
 * rounded before it leaves this module, and never written to storage or sent anywhere but the gig lists'
 * `fromLat` / `fromLng`. The server rounds again, so nothing finer than about a kilometre is ever used.
 */
export type Position = { lat: number; lng: number };

/** Two decimal places of a degree: about 1.1 km north–south and 0.7 km east–west in the UK. */
const PER_DEGREE = 100;

/** A position read again after this long (people move between gigs; reading costs battery). */
export const STALE_MS = 5 * 60_000;

/** The longest the lists wait for a first reading before they use the home city instead. */
export const FIRST_READ_TIMEOUT_MS = 2_000;

/** The position rounded to 0.01°. */
export function approximate(lat: number, lng: number): Position {
  return { lat: round(lat), lng: round(lng) };
}

function round(degrees: number): number {
  return Math.round(degrees * PER_DEGREE) / PER_DEGREE;
}

/** The query parameters that measure a list's distances from the phone, or none (the home city). */
export function distanceParams(here: Position | null): { fromLat?: number; fromLng?: number } {
  return here ? { fromLat: here.lat, fromLng: here.lng } : {};
}
