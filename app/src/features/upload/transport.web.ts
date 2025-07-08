import type { UploadSource, UploadTransport } from './types';

/**
 * Browser transport: the picked file is a `blob:` URL; `Blob.slice` gives a part without copying it.
 * The PUTs go straight to storage with plain `fetch` (no API token: the URL itself is the permission).
 * Reading the `ETag` response header works because the bucket's CORS rule exposes it.
 */
const blobs = new Map<string, Promise<Blob>>();

function blobOf(source: UploadSource): Promise<Blob> {
  let blob = blobs.get(source.uri);
  if (!blob) {
    blob = fetch(source.uri).then((response) => response.blob());
    blobs.set(source.uri, blob);
  }
  return blob;
}

async function put(url: string, headers: Record<string, string>, body: Blob): Promise<Response> {
  const response = await fetch(url, { method: 'PUT', headers, body });
  if (!response.ok) {
    throw new Error(`Storage answered ${response.status}`);
  }
  return response;
}

export const transport: UploadTransport = {
  async putWhole(url, headers, source) {
    await put(url, headers, await blobOf(source));
  },
  async putPart(url, source, offset, length) {
    const blob = await blobOf(source);
    const response = await put(url, {}, blob.slice(offset, offset + length));
    const etag = response.headers.get('ETag');
    if (!etag) {
      throw new Error('Storage did not return an ETag for the part');
    }
    return etag;
  },
  // The browser makes no copies.
  discardLeftovers() {},
};
