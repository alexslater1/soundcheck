import { Platform } from 'react-native';

import { env } from '@/lib/env';

import './searchParamsSize';

/**
 * The orval mutator: every generated API call goes through `customFetch`. It prefixes the
 * base URL, attaches the access token and the `X-Client` hint, and maps RFC 9457 Problem Details to a
 * typed `ApiError`.
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

  constructor(status: number, code: string, message: string, problem?: Problem) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.problem = problem;
  }
}

export type ClientKind = 'android' | 'web';

/** `X-Client` tells the API which refresh-token transport to use. */
export function clientKind(): ClientKind {
  return Platform.OS === 'web' ? 'web' : 'android';
}

type TokenProvider = () => string | null | Promise<string | null>;

let accessTokenProvider: TokenProvider = () => null;

/** Installed by the auth store; defaults to anonymous. */
export function setAccessTokenProvider(provider: TokenProvider): void {
  accessTokenProvider = provider;
}

export function resolveUrl(url: string): string {
  return /^https?:\/\//.test(url)
    ? url
    : `${env.apiBaseUrl}${url.startsWith('/') ? '' : '/'}${url}`;
}

export async function customFetch<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json, application/problem+json');
  headers.set('X-Client', clientKind());
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const token = await accessTokenProvider();
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(resolveUrl(url), { ...init, headers });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server', undefined);
  }

  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  const contentType = response.headers.get('content-type') ?? '';
  if (contentType.includes('json')) {
    try {
      const problem = (await response.json()) as Problem;
      return new ApiError(
        response.status,
        problem.code ?? `HTTP_${response.status}`,
        problem.detail ?? problem.title ?? response.statusText,
        problem,
      );
    } catch {
      // fall through to the generic error
    }
  }
  return new ApiError(
    response.status,
    `HTTP_${response.status}`,
    response.statusText || 'Request failed',
  );
}
