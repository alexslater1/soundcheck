import { fetch } from 'expo/fetch';

import { File, type FileHandle } from '@/lib/fileSystemNext';

import type { UploadSource, UploadTransport } from './types';

/**
 * Android transport: reads the byte range of a part from the file with a file handle (only that part
 * is in memory, 8 MiB at a time, never the whole video) and PUTs it straight to storage. `expo/fetch`
 * is used because it accepts binary bodies. No API token: the presigned URL is the permission.
 */
function read(source: UploadSource, offset: number, length: number): Uint8Array {
  const handle = new File(source.uri).open();
  try {
    handle.offset = offset;
    return readAtMost(handle, length);
  } finally {
    handle.close();
  }
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

async function put(
  url: string,
  headers: Record<string, string>,
  body: Uint8Array,
): Promise<Awaited<ReturnType<typeof fetch>>> {
  const response = await fetch(url, { method: 'PUT', headers, body });
  if (!response.ok) {
    throw new Error(`Storage answered ${response.status}`);
  }
  return response;
}

export const transport: UploadTransport = {
  async putWhole(url, headers, source) {
    await put(url, headers, read(source, 0, source.bytes));
  },
  async putPart(url, source, offset, length) {
    const response = await put(url, {}, read(source, offset, length));
    const etag = response.headers.get('ETag');
    if (!etag) {
      throw new Error('Storage did not return an ETag for the part');
    }
    return etag;
  },
};
