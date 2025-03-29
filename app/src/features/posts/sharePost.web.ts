/** The public address of a post, on whatever origin the web app is being served from. */
export function postUrl(postId: string): string {
  return `${window.location.origin}/post/${postId}`;
}

/** Web: copy the link to the clipboard. */
export async function sharePost(postId: string): Promise<'shared' | 'copied' | 'failed'> {
  try {
    await navigator.clipboard.writeText(postUrl(postId));
    return 'copied';
  } catch {
    return 'failed';
  }
}
