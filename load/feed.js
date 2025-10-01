// For You: each iteration is one visit, the first page and then 3 more pages with think
// time between them. 50 visits a second give the two target rates: 50 first pages and 150 later pages a second.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { API, REQUEST_TIMEOUT, SUMMARY_TREND_STATS, arrivalScenarios, authHeaders, thresholdsFor } from './lib/config.js';
import { loginPool, userFor } from './lib/auth.js';
import { writeSummary } from './lib/summary.js';

const VISITS_PER_S = 50;
const LATER_PAGES = 3;
const THINK_S = Number(__ENV.THINK_S || '1');
const ENDPOINTS = ['feed_first', 'feed_later'];

export const options = {
  scenarios: arrivalScenarios('visit', VISITS_PER_S, { seconds: 1 + LATER_PAGES * (THINK_S + 0.1) }),
  thresholds: thresholdsFor(ENDPOINTS),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '10m',
};

export function setup() {
  return { users: loginPool() };
}

export function visit(data) {
  const user = userFor(data.users);
  const params = (endpoint) => ({ headers: authHeaders(user.token), tags: { endpoint }, timeout: REQUEST_TIMEOUT });

  let res = http.get(`${API}/feed/home`, params('feed_first'));
  if (!check(res, { 'first page 200': (r) => r.status === 200 })) return;
  let cursor = res.json().nextCursor;
  for (let page = 0; page < LATER_PAGES && cursor; page += 1) {
    sleep(THINK_S);
    res = http.get(`${API}/feed/home?cursor=${encodeURIComponent(cursor)}`, params('feed_later'));
    if (!check(res, { 'later page 200': (r) => r.status === 200 })) return;
    cursor = res.json().nextCursor;
  }
}

export function handleSummary(data) {
  return writeSummary(data, { scenario: 'feed', endpoints: ENDPOINTS, iterationsPerSecond: VISITS_PER_S });
}
