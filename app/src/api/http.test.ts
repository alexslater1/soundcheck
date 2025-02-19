import { ApiError, customFetch, resolveUrl, setAccessTokenProvider } from './http';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function jsonResponse(status: number, body: unknown, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': contentType } });
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  setAccessTokenProvider(() => null);
});

describe('resolveUrl', () => {
  it('prefixes relative paths with the API base URL', () => {
    expect(resolveUrl('/api/v1/health')).toBe('http://localhost:8088/api/v1/health');
    expect(resolveUrl('api/v1/health')).toBe('http://localhost:8088/api/v1/health');
  });

  it('leaves absolute URLs alone', () => {
    expect(resolveUrl('https://cdn.example.com/x')).toBe('https://cdn.example.com/x');
  });
});

describe('customFetch', () => {
  it('returns the parsed body and sends the client hint', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { status: 'UP' }));

    const body = await customFetch<{ status: string }>('/api/v1/health');

    expect(body).toEqual({ status: 'UP' });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://localhost:8088/api/v1/health');
    expect(new Headers(init?.headers).get('X-Client')).toBe('android');
    expect(new Headers(init?.headers).get('Authorization')).toBeNull();
  });

  it('attaches the access token when one is available', async () => {
    setAccessTokenProvider(async () => 'jwt-123');
    fetchMock.mockResolvedValue(jsonResponse(200, {}));

    await customFetch('/api/v1/me');

    expect(new Headers(fetchMock.mock.calls[0]![1]?.headers).get('Authorization')).toBe(
      'Bearer jwt-123',
    );
  });

  it('maps Problem Details to ApiError', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        409,
        {
          type: 'https://soundcheck.app/problems/handle-taken',
          title: 'Conflict',
          status: 409,
          detail: 'Handle already taken',
          code: 'HANDLE_TAKEN',
        },
        'application/problem+json',
      ),
    );

    const error = await customFetch('/api/v1/users').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(409);
    expect(apiError.code).toBe('HANDLE_TAKEN');
    expect(apiError.message).toBe('Handle already taken');
    expect(apiError.problem?.type).toBe('https://soundcheck.app/problems/handle-taken');
  });

  it('returns undefined for empty success bodies (202/204)', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 202 }));
    await expect(customFetch('/api/v1/me', { method: 'DELETE' })).resolves.toBeUndefined();
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(customFetch('/api/v1/auth/logout', { method: 'POST' })).resolves.toBeUndefined();
  });

  it('maps non-JSON failures and network errors', async () => {
    fetchMock.mockResolvedValue(new Response('nope', { status: 502, statusText: 'Bad Gateway' }));
    await expect(customFetch('/x')).rejects.toMatchObject({ status: 502, code: 'HTTP_502' });

    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(customFetch('/x')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });
});
