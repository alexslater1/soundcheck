// Interaction events: 100 batches a second, each 20 events about posts the user was served.
// Events posted here are never rolled up: the perf API runs no scheduler.
import http from 'k6/http';
import { check } from 'k6';
import { API, REQUEST_TIMEOUT, SUMMARY_TREND_STATS, arrivalScenarios, authHeaders, thresholdsFor } from './lib/config.js';
import { followingPosts, loginPool } from './lib/auth.js';
import { writeSummary } from './lib/summary.js';

const RATE = 100;
const BATCH = 20;
const ENDPOINTS = ['events'];
// Roughly the dataset's own mix: mostly impressions and view starts.
const TYPES = ['IMPRESSION', 'IMPRESSION', 'IMPRESSION', 'IMPRESSION', 'VIEW_START', 'VIEW_START', 'WATCH_PROGRESS', 'COMPLETE'];

export const options = {
  scenarios: arrivalScenarios('send', RATE),
  thresholds: thresholdsFor(ENDPOINTS),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '10m',
};

export function setup() {
  const users = loginPool();
  const posts = followingPosts(users, BATCH);
  // Only users who were served something can report events about it.
  const served = users.map((user, i) => ({ ...user, posts: posts[i] })).filter((user) => user.posts.length > 0);
  console.log(`setup: ${served.length} users with served posts`);
  return { users: served };
}

export function send(data) {
  const user = data.users[(__VU - 1) % data.users.length];
  const now = new Date().toISOString();
  const events = [];
  for (let i = 0; i < BATCH; i += 1) {
    const type = TYPES[i % TYPES.length];
    events.push({
      postId: user.posts[i % user.posts.length],
      surface: 'FOLLOWING',
      type,
      position: i % user.posts.length,
      value: type === 'WATCH_PROGRESS' ? 0.5 : null,
      clientTs: now,
    });
  }
  const res = http.post(`${API}/events`, JSON.stringify({ events }), {
    headers: authHeaders(user.token),
    tags: { endpoint: 'events' },
    timeout: REQUEST_TIMEOUT,
  });
  check(res, { 'events 2xx': (r) => r.status >= 200 && r.status < 300 });
}

export function handleSummary(data) {
  return writeSummary(data, { scenario: 'events', endpoints: ENDPOINTS, iterationsPerSecond: RATE });
}
