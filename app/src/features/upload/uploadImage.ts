import { completeUpload, createUpload } from '@/api/generated/media/media';

import type { PreviewImage } from './previewFrame';
import { transport } from './transport';

/**
 * Uploads a small image the way the profile photo goes up: one presigned PUT, then "complete",
 * which checks and cleans it on the server. Resolves with its media id.
 */
export async function uploadImage(image: PreviewImage): Promise<string> {
  const ticket = await createUpload({
    kind: 'IMAGE',
    contentType: image.contentType,
    bytes: image.bytes,
    width: image.width,
    height: image.height,
  });
  if (ticket.strategy !== 'SINGLE' || !ticket.putUrl) {
    throw new Error('The server asked for a multipart upload of an image.');
  }
  await transport.putWhole(ticket.putUrl, ticket.headers ?? {}, {
    uri: image.uri,
    bytes: image.bytes,
  });
  await completeUpload(ticket.mediaId, {});
  return ticket.mediaId;
}
