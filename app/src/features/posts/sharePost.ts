import { Share } from 'react-native';

import { env } from '@/lib/env';

/** The public address of a post: the web app resolves `/post/{id}`. */
export function postUrl(postId: string): string {
  return `${env.apiBaseUrl}/post/${postId}`;
}

/** Android: the system share sheet (which includes "copy"). Resolves to what the user was shown. */
export async function sharePost(postId: string): Promise<'shared' | 'copied' | 'failed'> {
  try {
    await Share.share({ message: postUrl(postId) });
    return 'shared';
  } catch {
    return 'failed';
  }
}
