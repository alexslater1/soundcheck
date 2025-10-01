// The Following feed: one first page per iteration, 100 a second.
import http from 'k6/http';
import { check } from 'k6';
import { API, REQUEST_TIMEOUT, SUMMARY_TREND_STATS, arrivalScenarios, authHeaders, thresholdsFor } from './lib/config.js';
import { loginPool, userFor } from './lib/auth.js';
import { writeSummary } from './lib/summary.js';

const RATE = 100;
const ENDPOINTS = ['following'];

export const options = {
  scenarios: arrivalScenarios('page', RATE),
  thresholds: thresholdsFor(ENDPOINTS),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '10m',
};

export function setup() {
  return { users: loginPool() };
}

export function page(data) {
  const user = userFor(data.users);
  const res = http.get(`${API}/feed/following`, {
    headers: authHeaders(user.token),
    tags: { endpoint: 'following' },
    timeout: REQUEST_TIMEOUT,
  });
  check(res, { 'following 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return writeSummary(data, { scenario: 'following', endpoints: ENDPOINTS, iterationsPerSecond: RATE });
}
