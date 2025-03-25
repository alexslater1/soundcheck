/**
 * Which clips in a list may hold a live video player: the current one and its neighbours,
 * so swiping either way starts instantly, and never more than `size`. Everything else shows its poster.
 * A decoder and its buffers cost tens of megabytes each; a feed of twenty live players would not survive.
 */
export function playerWindow(
  order: readonly string[],
  currentId: string | null,
  size = 3,
): string[] {
  if (size < 1 || order.length === 0) {
    return [];
  }
  const index = currentId === null ? -1 : order.indexOf(currentId);
  if (index === -1) {
    return [];
  }
  // Centre the window on the current item, then slide it back inside the list at either end.
  const before = Math.floor((size - 1) / 2);
  const start = Math.max(0, Math.min(index - before, order.length - size));
  return order.slice(start, start + size);
}
