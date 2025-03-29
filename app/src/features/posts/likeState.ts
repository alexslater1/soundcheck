export type LikeState = { likedByMe: boolean; likeCount: number };

/** What the like button shows the instant it is pressed, before the server has answered. */
export function toggled(state: LikeState): LikeState {
  return state.likedByMe
    ? { likedByMe: false, likeCount: Math.max(0, state.likeCount - 1) }
    : { likedByMe: true, likeCount: state.likeCount + 1 };
}
