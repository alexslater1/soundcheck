import * as ImagePicker from 'expo-image-picker';

import { completeUpload, createUpload } from '@/api/generated/media/media';
import { updateMe } from '@/api/generated/me/me';
import { transport } from '@/features/upload/transport';

/** A photo picked for the profile, in the shape the upload needs. */
export type PickedImage = {
  uri: string;
  contentType: string;
  bytes: number;
  width: number | null;
  height: number | null;
};

/** The image types the server keeps (`UploadService`); the picker's square crop saves a JPEG. */
const TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

/** The picker's asset in the upload's shape; null when it is not an image the server accepts. */
export function toPickedImage(asset: ImagePicker.ImagePickerAsset): PickedImage | null {
  const contentType = asset.mimeType ?? 'image/jpeg';
  const bytes = asset.fileSize ?? asset.file?.size ?? 0;
  if (!TYPES.has(contentType) || bytes <= 0) {
    return null;
  }
  return {
    uri: asset.uri,
    contentType,
    bytes,
    width: asset.width > 0 ? asset.width : null,
    height: asset.height > 0 ? asset.height : null,
  };
}

/**
 * The system photo picker (no permission needed on Android; a file dialog on web), cropped square.
 * Null when the user cancels; throws when the picked file cannot be used.
 */
export async function pickAvatar(): Promise<PickedImage | null> {
  const picked = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ['images'],
    allowsEditing: true,
    aspect: [1, 1],
    quality: 0.8,
  });
  const asset = picked.canceled ? undefined : picked.assets[0];
  if (!asset) {
    return null;
  }
  const image = toPickedImage(asset);
  if (!image) {
    throw new Error('Choose a JPEG, PNG or WebP photo.');
  }
  return image;
}

/**
 * The new photo goes up like any other image (one presigned PUT, then "complete", which checks
 * and cleans it on the server) and becomes the profile photo. Resolves with the updated profile.
 */
export async function setAvatar(image: PickedImage) {
  const ticket = await createUpload({
    kind: 'IMAGE',
    contentType: image.contentType,
    bytes: image.bytes,
    width: image.width,
    height: image.height,
  });
  if (ticket.strategy !== 'SINGLE' || !ticket.putUrl) {
    throw new Error('The server asked for a multipart upload of a photo.');
  }
  await transport.putWhole(ticket.putUrl, ticket.headers ?? {}, {
    uri: image.uri,
    bytes: image.bytes,
  });
  await completeUpload(ticket.mediaId, {});
  return updateMe({ avatarMediaId: ticket.mediaId });
}
