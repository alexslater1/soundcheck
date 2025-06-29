import { Platform } from 'react-native';

import { env } from '@/lib/env';

/**
 * The orval mutator: every generated API call goes through `customFetch`. It prefixes the
 * base URL, attaches the access token and the `X-Client` hint, maps RFC 9457 Problem Details to a typed
 * `ApiError`, and on 401 refreshes the session **single-flight** (one refresh in progress; concurrent
 * 401s wait for it) and retries the original request once.
 */

/** RFC 9457 Problem Details as the API emits them. */
export type Problem = {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  code?: string;
  errors?: { field: string; message: string }[];
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: Problem | undefined;
  /** How long the server asked us to wait (its `Retry-After`, e.g. on a 429), when it said. */
  readonly retryAfterSeconds: number | undefined;

  constructor(
    status: number,
    code: string,
    message: string,
    problem?: Problem,
    retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.problem = problem;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** `Retry-After` is whole seconds or an HTTP date (RFC 9110 §10.2.3); undefined when absent or unreadable. */
export function parseRetryAfter(
  value: string | null,
  now: number = Date.now(),
): number | undefined {
  if (!value) {
    return undefined;
  }
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) {
    return Number(trimmed);
  }
  const at = Date.parse(trimmed);
  return Number.isNaN(at) ? undefined : Math.max(0, Math.ceil((at - now) / 1000));
}

export type ClientKind = 'android' | 'web';

/** `X-Client` tells the API which refresh-token transport to use. */
export function clientKind(): ClientKind {
  return Platform.OS === 'web' ? 'web' : 'android';
}

type TokenProvider = () => string | null | Promise<string | null>;
/** Performs one refresh and returns the new access token, or null when the session is gone. */
type RefreshHandler = () => Promise<string | null>;

let accessTokenProvider: TokenProvider = () => null;
let refreshHandler: RefreshHandler | null = null;
let refreshInFlight: Promise<string | null> | null = null;

/** Installed by the auth store; defaults to anonymous. */
export function setAccessTokenProvider(provider: TokenProvider): void {
  accessTokenProvider = provider;
}

/** Installed by the auth feature; null disables refresh-and-retry. */
export function setRefreshHandler(handler: RefreshHandler | null): void {
  refreshHandler = handler;
  refreshInFlight = null;
}

/** Single-flight: every caller during a refresh awaits the same promise. */
export function refreshOnce(): Promise<string | null> {
  if (!refreshHandler) {
    return Promise.resolve(null);
  }
  if (!refreshInFlight) {
    refreshInFlight = refreshHandler()
      .catch(() => null)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

export function resolveUrl(url: string): string {
  return /^https?:\/\//.test(url)
    ? url
    : `${env.apiBaseUrl}${url.startsWith('/') ? '' : '/'}${url}`;
}

export type FetchOptions = RequestInit & {
  /** Set on the auth endpoints themselves so a 401 there never triggers a refresh loop. */
  skipRefresh?: boolean;
};

export async function customFetch<T>(url: string, init: FetchOptions = {}): Promise<T> {
  const { skipRefresh, ...requestInit } = init;
  const token = await accessTokenProvider();
  let response = await send(url, requestInit, token);

  if (response.status === 401 && !skipRefresh && refreshHandler) {
    const fresh = await refreshOnce();
    if (fresh) {
      response = await send(url, requestInit, fresh);
    }
  }

  if (!response.ok) {
    throw await toApiError(response);
  }
  // 202/204 and other empty bodies (e.g. DELETE /me) must not be parsed as JSON.
  const text = await response.text();
  if (text.length === 0) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

async function send(url: string, init: RequestInit, token: string | null): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json, application/problem+json');
  headers.set('X-Client', clientKind());
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  try {
    // credentials: 'include' lets the web refresh cookie travel to /api/v1/auth/*.
    return await fetch(resolveUrl(url), { credentials: 'include', ...init, headers });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server', undefined);
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  const contentType = response.headers.get('content-type') ?? '';
  const retryAfter = parseRetryAfter(response.headers.get('retry-after'));
  if (contentType.includes('json')) {
    try {
      const problem = (await response.json()) as Problem;
      return new ApiError(
        response.status,
        problem.code ?? `HTTP_${response.status}`,
        problem.detail ?? problem.title ?? response.statusText,
        problem,
        retryAfter,
      );
    } catch {
      // fall through to the generic error
    }
  }
  return new ApiError(
    response.status,
    `HTTP_${response.status}`,
    response.statusText || 'Request failed',
    undefined,
    retryAfter,
  );
}
