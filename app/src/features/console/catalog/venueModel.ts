import type { VenueMergeCount, VenueMergePreview } from '@/api/generated/model';

/** The UK box the server checks: mainland, Northern Ireland and the islands. */
export const UK_BOX = { minLat: 49.8, maxLat: 60.9, minLng: -8.7, maxLng: 1.8 } as const;

/** The point on OpenStreetMap, as the server builds it (six decimals, zoomed to a street). */
export function osmUrl(lat: number, lng: number): string {
  const la = lat.toFixed(6);
  const lo = lng.toFixed(6);
  return `https://www.openstreetmap.org/?mlat=${la}&mlon=${lo}#map=18/${la}/${lo}`;
}

export type LatLng = { lat: number; lng: number };

/**
 * Reads what the operator typed or pasted: two numbers, latitude first, separated by a comma or spaces
 * ("53.4631, -2.2913", as OpenStreetMap and Google Maps copy them). Null when it isn't two numbers.
 */
export function parseLatLng(text: string): LatLng | null {
  const parts = text
    .trim()
    .split(/[\s,]+/)
    .filter((p) => p.length > 0);
  if (parts.length !== 2) return null;
  const [lat, lng] = parts.map(Number);
  if (lat === undefined || lng === undefined || !Number.isFinite(lat) || !Number.isFinite(lng)) {
    return null;
  }
  return { lat, lng };
}

/** Inside the server's UK box; a swapped pair (longitude first) falls outside it. */
export function inUk({ lat, lng }: LatLng): boolean {
  return (
    lat >= UK_BOX.minLat && lat <= UK_BOX.maxLat && lng >= UK_BOX.minLng && lng <= UK_BOX.maxLng
  );
}

/** Why a venue is on the suspect list, in words. */
export function reasonLabel(reason: string): string {
  switch (reason) {
    case 'FAN_ADDED':
      return 'Added by a fan';
    case 'CITY_CENTRE':
      return "At a town's centre point";
    case 'NO_LOCATION':
      return 'No location';
    default:
      return reason;
  }
}

const LABELS: Record<string, [one: string, many: string]> = {
  gigs: ['gig', 'gigs'],
  listings: ["source's id for it", "sources' ids for it"],
  siblings: ['sibling room', 'sibling rooms'],
  savedSearches: ['saved search', 'saved searches'],
  followers: ['follower', 'followers'],
};

/** "312 gigs", "1 follower"; an unknown key is shown as it is. */
export function venueCountLabel({ what, count }: VenueMergeCount): string {
  const words = LABELS[what];
  if (!words) return `${count} ${what}`;
  return `${count} ${count === 1 ? words[0] : words[1]}`;
}

/** Warnings that don't refuse a venue merge but should make the operator look twice. */
export function venueMergeWarnings(preview: VenueMergePreview): string[] {
  const warnings: string[] = [];
  if (preview.distanceMetres != null && preview.distanceMetres > 1000) {
    const km = (preview.distanceMetres / 1000).toFixed(1);
    warnings.push(`The two venues are ${km} km apart.`);
  }
  if (preview.sameNight.length > 0) {
    const n = preview.sameNight.length;
    warnings.push(
      `${n} gig${n === 1 ? '' : 's'} will share a night with a gig at the venue that stays: check them on the Merge tab afterwards.`,
    );
  }
  return warnings;
}

/** The confirm dialog's lines: what moves, then what happens to the duplicate itself. */
export function venueMergeChanges(preview: VenueMergePreview): string[] {
  const into = preview.into.name;
  const moving = preview.moving
    .filter((m) => m.count > 0)
    .map((m) => `Move ${venueCountLabel(m)} to ${into}`);
  return [
    ...(moving.length > 0 ? moving : ['Move nothing: the duplicate has no gigs or followers']),
    `Delete ${preview.from.name}`,
    `Send its old link, and its name in new listings nearby, to ${into}`,
  ];
}
