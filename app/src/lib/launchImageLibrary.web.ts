import {
  type ImagePickerAsset,
  type ImagePickerOptions,
  type ImagePickerResult,
  MediaTypeOptions,
} from 'expo-image-picker';

/**
 * expo-image-picker's web library picker as SDK 54 and later have it (expo/expo#37447). SDK 53's reads
 * every chosen file into a base64 `data:` URL, and when the browser cannot load a video's metadata
 * from it (a Chromium without H.264 cannot) it hands back only that URL: no `file`, size, name or
 * type, so the clip looks empty. This one gives a `blob:` URL, as the app expects (it is stored with
 * the upload, so it must stay short), and keeps the file's name, size and type when the metadata
 * cannot be read (width, height and length are then 0, i.e. unknown).
 */
export async function launchImageLibraryAsync(
  options: ImagePickerOptions = {},
): Promise<ImagePickerResult> {
  // SSR guard
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return { canceled: true, assets: null };
  }
  const { mediaTypes = ['images'], allowsMultipleSelection = false, base64 = false } = options;
  return await openFileBrowserAsync(parseMediaTypes(mediaTypes), allowsMultipleSelection, base64);
}

type MediaType = 'images' | 'videos' | 'livePhotos';

const MEDIA_TYPE_INPUT: Record<MediaType, string> = {
  images: 'image/*',
  videos: 'video/mp4,video/quicktime,video/x-m4v,video/*',
  livePhotos: '',
};

function parseMediaTypes(mediaTypes: NonNullable<ImagePickerOptions['mediaTypes']>): MediaType[] {
  switch (mediaTypes) {
    case MediaTypeOptions.Images:
      return ['images'];
    case MediaTypeOptions.Videos:
      return ['videos'];
    case MediaTypeOptions.All:
      return ['images', 'videos'];
    default:
      return typeof mediaTypes === 'string' ? [mediaTypes] : mediaTypes;
  }
}

function createMediaTypeFormat(mediaTypes: MediaType[]): string {
  const filtered = mediaTypes.filter((mediaType) => mediaType !== 'livePhotos');
  if (filtered.length === 0) {
    return 'image/*';
  }
  let result = '';
  for (const mediaType of filtered) {
    // Make sure the types don't repeat
    if (!result.includes(MEDIA_TYPE_INPUT[mediaType])) {
      result = result.concat(',', MEDIA_TYPE_INPUT[mediaType]);
    }
  }
  return result;
}

function openFileBrowserAsync(
  mediaTypes: MediaType[],
  allowsMultipleSelection: boolean,
  base64: boolean,
): Promise<ImagePickerResult> {
  const input = document.createElement('input');
  input.style.display = 'none';
  input.setAttribute('type', 'file');
  input.setAttribute('accept', createMediaTypeFormat(mediaTypes));
  input.setAttribute('id', String(Math.random()));
  input.setAttribute('data-testid', 'file-input');
  if (allowsMultipleSelection) {
    input.setAttribute('multiple', 'multiple');
  }
  document.body.appendChild(input);

  return new Promise((resolve, reject) => {
    input.addEventListener('change', async () => {
      if (input.files?.length) {
        const files = allowsMultipleSelection ? Array.from(input.files) : [input.files[0]];
        try {
          const assets = await Promise.all(
            files
              .filter((file): file is File => file != null)
              .map((file) => readFile(file, base64)),
          );
          resolve({ canceled: false, assets });
        } catch (error) {
          reject(error);
        }
      } else {
        resolve({ canceled: true, assets: null });
      }
      document.body.removeChild(input);
    });
    input.addEventListener('cancel', () => {
      input.dispatchEvent(new Event('change'));
    });
    input.dispatchEvent(new MouseEvent('click'));
  });
}

function getImageMetadata(blobUrl: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve) => {
    const image = new Image();
    image.onload = () => {
      resolve({
        width: image.naturalWidth ?? image.width,
        height: image.naturalHeight ?? image.height,
      });
    };
    image.onerror = () => resolve({ width: 0, height: 0 });
    image.src = blobUrl;
  });
}

function getVideoMetadata(
  blobUrl: string,
): Promise<{ width: number; height: number; duration: number }> {
  return new Promise((resolve) => {
    const video = document.createElement('video');
    video.preload = 'metadata';
    video.onloadedmetadata = () => {
      resolve({ width: video.videoWidth, height: video.videoHeight, duration: video.duration });
    };
    video.onerror = () => resolve({ width: 0, height: 0, duration: 0 });
    video.src = blobUrl;
  });
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => {
      reject(new Error('Failed to read the selected media because the operation failed.'));
    };
    reader.onload = () => {
      const result = reader.result;
      if (typeof result !== 'string') {
        reject(new Error('Failed to read file as base64'));
        return;
      }
      // Remove the data URL prefix to get just the base64 data
      resolve(result.split(',')[1] ?? '');
    };
    reader.readAsDataURL(file);
  });
}

async function readFile(targetFile: File, base64: boolean): Promise<ImagePickerAsset> {
  const mimeType = targetFile.type;
  const isImage = mimeType.startsWith('image/');
  if (!isImage && !mimeType.startsWith('video/')) {
    throw new Error(`Unsupported file type: ${mimeType}. Only images and videos are supported.`);
  }
  const uri = URL.createObjectURL(targetFile);
  const metadata: { width: number; height: number; duration?: number } = isImage
    ? await getImageMetadata(uri)
    : await getVideoMetadata(uri);
  const encoded = base64 ? await readFileAsBase64(targetFile) : undefined;
  return {
    uri,
    width: metadata.width,
    height: metadata.height,
    type: isImage ? 'image' : 'video',
    mimeType,
    fileName: targetFile.name,
    fileSize: targetFile.size,
    file: targetFile,
    ...(metadata.duration !== undefined && { duration: metadata.duration }),
    ...(encoded && { base64: encoded }),
  };
}
