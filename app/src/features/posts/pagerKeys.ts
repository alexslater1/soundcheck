/**
 * Keyboard paging for the feed on the web: pure, so the key map is unit-tested without a browser.
 */

export type KeyPress = {
  key: string;
  altKey: boolean;
  ctrlKey: boolean;
  metaKey: boolean;
  /** The key went to a text field (the comment box): typing must never turn the page. */
  inTextField: boolean;
};

const NEXT = new Set(['ArrowDown', 'PageDown', 'j']);
const PREVIOUS = new Set(['ArrowUp', 'PageUp', 'k']);

/** +1 for the next post, -1 for the previous one, null for any other key. */
export function pageStep(press: KeyPress): 1 | -1 | null {
  if (press.altKey || press.ctrlKey || press.metaKey || press.inTextField) return null;
  if (NEXT.has(press.key)) return 1;
  if (PREVIOUS.has(press.key)) return -1;
  return null;
}

/** The page to show after a step, kept inside the list; null when there is nowhere to go. */
export function targetIndex(current: number, step: 1 | -1, count: number): number | null {
  if (count === 0) return null;
  const from = current < 0 ? 0 : current;
  const to = Math.min(Math.max(from + step, 0), count - 1);
  return to === from ? null : to;
}

/**
 * Where each page of the feed starts, for the list to snap to: with a refresh header,
 * offset 0 shows the header and the first post starts below it; every post is one page tall.
 */
export function pageOffsets(count: number, pageHeight: number, header: number): number[] {
  const pages = Array.from({ length: count }, (_, index) => header + pageHeight * index);
  return header > 0 ? [0, ...pages] : pages;
}

/** The list came to rest with its refresh header fully in view: time to refresh. */
export function restsOnHeader(offsetY: number, header: number): boolean {
  return header > 0 && offsetY <= 1;
}
