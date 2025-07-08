import { uploadAsync } from 'expo-file-system';

import { Directory, File, type FileHandle, Paths } from '@/lib/fileSystemNext';

import type { UploadSource, UploadTransport } from './types';

/**
 * Android transport: the bytes go from the file to storage natively (`uploadAsync`, OkHttp streaming
 * the file from disk), never through a JavaScript array. The earlier version read
 * each 8 MiB part into JS with `readBytes` and sent it with `expo/fetch`, which copied it again into
 * a Java byte array that lived until the PUT finished: four parts in flight, two clips at once, and
 * the 256 MB Java heap ran out.
 *
 * A multipart part is a byte range, and the native upload sends whole files only, so the range is
 * first copied into a temporary file in the cache, 1 MiB at a time (at most 1 MiB of the video is in
 * memory), and that file is uploaded and deleted. No API token: the presigned URL is the permission.
 */
export const COPY_CHUNK = 1024 * 1024;

/** Where the part copies live: one folder, so leftovers from an app killed mid-part can be found. */
function partsFolder(): Directory {
  return new Directory(Paths.cache, 'upload-parts');
}

/** Copies `length` bytes of `uri` starting at `offset` into a new cache file. */
export function copyRange(uri: string, offset: number, length: number): File {
  const folder = partsFolder();
  // `create` throws when the folder is already there.
  if (!folder.exists) {
    folder.create({ intermediates: true });
  }
  const part = new File(folder, `part-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  part.create();
  const from = new File(uri).open();
  let to: FileHandle | null = null;
  try {
    to = part.open();
    from.offset = offset;
    for (let copied = 0; copied < length; ) {
      const bytes = readAtMost(from, Math.min(COPY_CHUNK, length - copied));
      if (bytes.byteLength === 0) {
        throw new Error('The video file is shorter than expected');
      }
      to.writeBytes(bytes);
      copied += bytes.byteLength;
    }
  } catch (error) {
    to?.close();
    part.delete();
    throw error;
  } finally {
    from.close();
  }
  to.close();
  return part;
}

/** Up to `length` bytes from the handle's offset; none at or past the end of the file. */
function readAtMost(handle: FileHandle, length: number): Uint8Array {
  // `readBytes` throws instead of returning nothing when the offset is past the end.
  const size = handle.size;
  if (size !== null && (handle.offset ?? 0) >= size) {
    return new Uint8Array(0);
  }
  return handle.readBytes(length);
}

/** PUTs a file and returns the response headers; fails on anything but 2xx. */
async function put(
  file: File,
  url: string,
  headers: Record<string, string>,
): Promise<Record<string, string>> {
  // The upload's own message for a missing file ("Directory for … doesn't exist") is not one
  // `plainReason` recognises.
  if (!file.exists) {
    throw new Error('Unable to upload a file: File does not exist');
  }
  const result = await uploadAsync(url, file.uri, { httpMethod: 'PUT', headers });
  if (result.status < 200 || result.status >= 300) {
    throw new Error(`Storage answered ${result.status}`);
  }
  return result.headers;
}

/** Header names arrive in whatever case the server (or OkHttp) used. */
function header(headers: Record<string, string>, name: string): string | undefined {
  const key = Object.keys(headers).find((k) => k.toLowerCase() === name.toLowerCase());
  return key === undefined ? undefined : headers[key];
}

export const transport: UploadTransport = {
  async putWhole(url, headers, source) {
    await put(new File(source.uri), url, headers);
  },
  async putPart(url, source: UploadSource, offset, length) {
    const part = copyRange(source.uri, offset, length);
    try {
      const etag = header(await put(part, url, {}), 'ETag');
      if (!etag) {
        throw new Error('Storage did not return an ETag for the part');
      }
      return etag;
    } finally {
      part.delete();
    }
  },
  discardLeftovers() {
    // Only called while no upload is sending (the runner checks), so no part here is in use: they
    // are what an app killed in the middle of a part left behind.
    try {
      const folder = partsFolder();
      if (folder.exists) {
        folder.delete();
      }
    } catch {
      // The system clears the cache directory when it needs the space.
    }
  },
};
