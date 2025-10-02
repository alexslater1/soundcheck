// Uploads: create, PUT 2 MB to the presigned URL, complete. 20 a second at full rate; the
// target is create's latency. Objects land in the perf stack's own bucket (soundcheck-media-perf) and the complete step
// queues a transcode job on soundcheck-transcode-perf, which nothing consumes: the perf stack doesn't transcode.
import http from 'k6/http';
import { check } from 'k6';
import { API, REQUEST_TIMEOUT, SUMMARY_TREND_STATS, arrivalScenarios, authHeaders, thresholdsFor } from './lib/config.js';
import { loginPool, userFor } from './lib/auth.js';
import { writeSummary } from './lib/summary.js';

const RATE = 20;
const BYTES = 2 * 1024 * 1024;
const ENDPOINTS = ['upload_create'];

// One 2 MB body, built once per VU at init: the bytes don't matter, nothing transcodes them.
const BODY = new Uint8Array(BYTES).map((_, i) => i % 251).buffer;

export const options = {
  scenarios: arrivalScenarios('upload', RATE, { seconds: 1 }),
  thresholds: thresholdsFor(ENDPOINTS),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '10m',
};

export function setup() {
  return { users: loginPool() };
}

export function upload(data) {
  const user = userFor(data.users);
  const headers = authHeaders(user.token);
  const create = http.post(
    `${API}/media/uploads`,
    JSON.stringify({ kind: 'VIDEO', contentType: 'video/mp4', bytes: BYTES, durationMs: 10000, width: 1280, height: 720 }),
    { headers, tags: { endpoint: 'upload_create' }, timeout: REQUEST_TIMEOUT },
  );
  if (!check(create, { 'create 201': (r) => r.status === 201 })) return;
  const ticket = create.json();

  const put = http.put(ticket.putUrl, BODY, {
    headers: { 'Content-Type': 'video/mp4', ...(ticket.headers || {}) },
    tags: { endpoint: 'upload_put', name: 'presigned PUT' },
    timeout: REQUEST_TIMEOUT,
  });
  if (!check(put, { 'put 200': (r) => r.status === 200 })) return;

  const complete = http.post(`${API}/media/uploads/${ticket.mediaId}/complete`, '{}', {
    headers,
    tags: { endpoint: 'upload_complete', name: `${API}/media/uploads/{id}/complete` },
    timeout: REQUEST_TIMEOUT,
  });
  check(complete, { 'complete 2xx': (r) => r.status >= 200 && r.status < 300 });
}

export function handleSummary(data) {
  return writeSummary(data, { scenario: 'upload', endpoints: ENDPOINTS, iterationsPerSecond: RATE });
}
