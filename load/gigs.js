// Gigs near a city in the next 30 days: 100 searches a second, each from a random city.
import http from 'k6/http';
import { check } from 'k6';
import { API, REQUEST_TIMEOUT, SUMMARY_TREND_STATS, arrivalScenarios, authHeaders, pick, thresholdsFor } from './lib/config.js';
import { loginPool, userFor } from './lib/auth.js';
import { writeSummary } from './lib/summary.js';

const RATE = 100;
const RADIUS_KM = 25;
const ENDPOINTS = ['gigs_near'];
// A fixed list, so every run asks the same mix: big cities (many gigs) and smaller ones (few).
const CITIES = [
  ['London', 51.5072, -0.1276],
  ['Manchester', 53.4808, -2.2426],
  ['Birmingham', 52.4862, -1.8904],
  ['Glasgow', 55.8642, -4.2518],
  ['Leeds', 53.8008, -1.5491],
  ['Bristol', 51.4545, -2.5879],
  ['Liverpool', 53.4084, -2.9916],
  ['Newcastle upon Tyne', 54.9783, -1.6178],
  ['Cardiff', 51.4816, -3.1791],
  ['Brighton', 50.8225, -0.1372],
  ['Norwich', 52.6309, 1.2974],
  ['Exeter', 50.7184, -3.5339],
];

export const options = {
  scenarios: arrivalScenarios('search', RATE),
  thresholds: thresholdsFor(ENDPOINTS),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '10m',
};

export function setup() {
  return { users: loginPool() };
}

export function search(data) {
  const user = userFor(data.users);
  const [, lat, lng] = pick(CITIES);
  const from = new Date();
  const to = new Date(from.getTime() + 30 * 24 * 3600 * 1000);
  const query = `lat=${lat}&lng=${lng}&radiusKm=${RADIUS_KM}&from=${from.toISOString()}&to=${to.toISOString()}`;
  const res = http.get(`${API}/gigs?${query}`, {
    headers: authHeaders(user.token),
    tags: { endpoint: 'gigs_near' },
    timeout: REQUEST_TIMEOUT,
  });
  check(res, { 'gigs 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return writeSummary(data, { scenario: 'gigs', endpoints: ENDPOINTS, iterationsPerSecond: RATE });
}
