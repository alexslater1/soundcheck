/** The largest box of the clip's shape that fits into the given space. */
export function fitClip(aspect: number, maxWidth: number, maxHeight: number) {
  const width = Math.min(maxWidth, maxHeight * aspect);
  return { width, height: width / aspect };
}

/** How far a clip's shape may differ from the page's before it is fitted instead of filling the page. */
export const FILL_TOLERANCE = 0.25;

/**
 * How a clip sits on a full-screen page. A clip whose shape is close to the page's (a 9:16
 * phone clip on a 20:9 phone) fills the page and loses a sliver at the edges, as in other short-video
 * apps; letterboxing it left black bands above and below. A clip of another shape (landscape) is
 * fitted whole, over a blurred copy of its poster instead of black.
 */
export function pageFit(
  aspect: number,
  width: number,
  height: number,
): { fill: boolean; width: number; height: number } {
  const ratio = aspect / (width / height);
  if (ratio <= 1 + FILL_TOLERANCE && ratio >= 1 / (1 + FILL_TOLERANCE)) {
    return { fill: true, width, height };
  }
  return { fill: false, ...fitClip(aspect, width, height) };
}
