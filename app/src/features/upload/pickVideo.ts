import * as ImagePicker from 'expo-image-picker';
import { Platform } from 'react-native';

import type { PickedVideo } from './types';
import { MAX_DURATION_MS } from './validate';

export type PickResult =
  | { kind: 'picked'; videos: PickedVideo[] }
  | { kind: 'cancelled' }
  | { kind: 'denied' };

/** How many clips can be picked at once (several clips from one gig). */
export const MAX_CLIPS_AT_ONCE = 10;

/** The picker's asset in the app's own shape. Pure, so the unit differences are tested. */
export function toPicked(asset: ImagePicker.ImagePickerAsset, platform: string): PickedVideo {
  // A photo has no length. Android reports a video's length in milliseconds; the web
  // implementation reports the video element's `duration`, which is seconds. Zero or missing means the
  // metadata could not be read.
  const photo = asset.type === 'image' || (asset.mimeType?.startsWith('image/') ?? false);
  const duration = photo ? 0 : (asset.duration ?? 0);
  const durationMs =
    duration > 0 ? Math.round(platform === 'web' ? duration * 1000 : duration) : null;
  // Android gives a video's stored width and height with its `rotation` (missing from the types): a
  // clip filmed upright is stored 1920 × 1080 turned by 90°. Swapped, they are the ones it is shown in.
  const rotation = (asset as { rotation?: number | null }).rotation ?? 0;
  const turned = rotation % 180 !== 0;
  const width = turned ? asset.height : asset.width;
  const height = turned ? asset.width : asset.height;
  return {
    uri: asset.uri,
    fileName: asset.fileName ?? null,
    contentType: asset.mimeType ?? (photo ? 'image/jpeg' : 'video/mp4'),
    bytes: asset.fileSize ?? asset.file?.size ?? 0,
    durationMs,
    width: width > 0 ? width : null,
    height: height > 0 ? height : null,
  };
}

const OPTIONS: ImagePicker.ImagePickerOptions = {
  mediaTypes: ['videos'],
  // Enforced by the camera and, where the platform supports it, by the picker; checked again afterwards.
  videoMaxDuration: MAX_DURATION_MS / 1000 - 1,
  allowsMultipleSelection: false,
};

export function result(picked: ImagePicker.ImagePickerResult, platform: string): PickResult {
  const assets = picked.canceled ? [] : picked.assets;
  return assets.length > 0
    ? { kind: 'picked', videos: assets.map((asset) => toPicked(asset, platform)) }
    : { kind: 'cancelled' };
}

/**
 * Choose existing videos and photos, up to ten at once: the system photo picker on Android (no
 * permission needed), a file dialog on web. They are kept in the order they were picked. A photo is
 * taken as it is stored (quality 1: the picker copies the file, so its reported size is the size sent).
 */
export async function pickFromLibrary(): Promise<PickResult> {
  return result(
    await ImagePicker.launchImageLibraryAsync({
      ...OPTIONS,
      mediaTypes: ['images', 'videos'],
      allowsMultipleSelection: true,
      selectionLimit: MAX_CLIPS_AT_ONCE,
      orderedSelection: true,
    }),
    Platform.OS,
  );
}

/** Record a new clip with the system camera (Android). Videos only: the Create tab is for clips first. */
export async function recordWithCamera(): Promise<PickResult> {
  const permission = await ImagePicker.requestCameraPermissionsAsync();
  if (!permission.granted) {
    return { kind: 'denied' };
  }
  return result(await ImagePicker.launchCameraAsync(OPTIONS), Platform.OS);
}

/**
 * Android may kill the app while the system camera is open, because the phone wants the memory
 * back. The recording then has nobody to go to, and the app starts again on Home (seen on the
 * phone, 2025-09-03). expo-image-picker keeps the camera's answer for the next start; this collects
 * it. Null when nothing is waiting (always, off Android), when the camera was cancelled, or when it
 * failed.
 */
export async function pendingRecording(): Promise<PickResult | null> {
  const pending = await ImagePicker.getPendingResultAsync();
  if (!pending || !('canceled' in pending)) {
    return null;
  }
  const picked = result(pending, Platform.OS);
  return picked.kind === 'picked' ? picked : null;
}
