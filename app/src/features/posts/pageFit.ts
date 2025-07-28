/** The largest box of the clip's shape that fits into the given space. */
export function fitClip(aspect: number, maxWidth: number, maxHeight: number) {
  const width = Math.min(maxWidth, maxHeight * aspect);
  return { width, height: width / aspect };
}

/** How far a clip's shape may differ from the page's before it is fitted instead of filling the page. */
export const FILL_TOLERANCE = 0.25;

/**
 * How one clip of a post sits in the post's box. A clip whose shape is close to the box's fills it,
 * losing a sliver at the edges (null: no fitting needed). A clip of another shape (a landscape clip in a
 * portrait post) is shown whole and centred; `width` and `height` are fractions (0–1) of the box, and the
 * rest of the box shows a blurred copy of the clip's own poster instead of black bars.
 */
export function clipInBox(
  clipAspect: number | null,
  boxAspect: number,
): { width: number; height: number } | null {
  if (clipAspect === null || clipAspect <= 0) {
    return null;
  }
  const ratio = clipAspect / boxAspect;
  if (ratio <= 1 + FILL_TOLERANCE && ratio >= 1 / (1 + FILL_TOLERANCE)) {
    return null;
  }
  return ratio > 1 ? { width: 1, height: 1 / ratio } : { width: ratio, height: 1 };
}

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
