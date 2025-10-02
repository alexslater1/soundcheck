// The mix: 40 % feed, 20 % events, 15 % gigs, 10 % profile, 10 % post detail, 5 % like or
// comment, at MIXED_RATE operations a second (default 250). A feed operation is one page: a VU's first page,
// then its next 3 pages, then a new visit, so first and later pages keep the targets' 1 : 3 ratio.
import http from 'k6/http';
import { check } from 'k6';
import { API, REQUEST_TIMEOUT, SUMMARY_TREND_STATS, arrivalScenarios, authHeaders, pick, recordOnly, thresholdsFor } from './lib/config.js';
import { followingPosts, loginPool } from './lib/auth.js';
import { writeSummary } from './lib/summary.js';

const RATE = Number(__ENV.MIXED_RATE || '250');
const TARGETED = ['feed_first', 'feed_later', 'events', 'gigs_near'];
const UNTARGETED = ['profile', 'post_detail', 'interact'];
const MIX = [
  [0.4, feedPage],
  [0.2, events],
  [0.15, gigs],
  [0.1, profile],
  [0.1, postDetail],
  [0.05, interact],
];
const CITIES = [
  [51.5072, -0.1276],
  [53.4808, -2.2426],
  [52.4862, -1.8904],
  [55.8642, -4.2518],
  [53.8008, -1.5491],
  [51.4545, -2.5879],
];

export const options = {
  scenarios: arrivalScenarios('operation', RATE),
  thresholds: { ...thresholdsFor(TARGETED), ...recordOnly(UNTARGETED) },
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '10m',
};

export function setup() {
  const users = loginPool();
  const posts = followingPosts(users, 20);
  return { users: users.map((user, i) => ({ ...user, posts: posts[i] })) };
}

// Per-VU state: where this VU's current For You visit is.
let cursor = null;
let laterPages = 0;

function params(user, endpoint) {
  return { headers: authHeaders(user.token), tags: { endpoint }, timeout: REQUEST_TIMEOUT };
}

function feedPage(user) {
  if (cursor && laterPages < 3) {
    const res = http.get(`${API}/feed/home?cursor=${encodeURIComponent(cursor)}`, params(user, 'feed_later'));
    check(res, { 'later page 200': (r) => r.status === 200 });
    laterPages += 1;
    cursor = res.status === 200 ? res.json().nextCursor : null;
    return;
  }
  const res = http.get(`${API}/feed/home`, params(user, 'feed_first'));
  check(res, { 'first page 200': (r) => r.status === 200 });
  laterPages = 0;
  cursor = res.status === 200 ? res.json().nextCursor : null;
}

function events(user, data) {
  const posts = user.posts.length ? user.posts : pick(data.users.filter((u) => u.posts.length)).posts;
  const now = new Date().toISOString();
  const batch = posts.slice(0, 20).map((postId, i) => ({ postId, surface: 'FOLLOWING', type: i % 2 ? 'VIEW_START' : 'IMPRESSION', position: i, clientTs: now }));
  const res = http.post(`${API}/events`, JSON.stringify({ events: batch }), params(user, 'events'));
  check(res, { 'events 2xx': (r) => r.status >= 200 && r.status < 300 });
}

function gigs(user) {
  const [lat, lng] = pick(CITIES);
  const from = new Date();
  const to = new Date(from.getTime() + 30 * 24 * 3600 * 1000);
  const res = http.get(
    `${API}/gigs?lat=${lat}&lng=${lng}&radiusKm=25&from=${from.toISOString()}&to=${to.toISOString()}`,
    params(user, 'gigs_near'),
  );
  check(res, { 'gigs 200': (r) => r.status === 200 });
}

function profile(user, data) {
  const other = pick(data.users);
  const res = http.get(`${API}/users/${other.handle}`, { ...params(user, 'profile'), tags: { endpoint: 'profile', name: `${API}/users/{handle}` } });
  check(res, { 'profile 200': (r) => r.status === 200 });
}

function somePost(user, data) {
  const posts = user.posts.length ? user.posts : pick(data.users.filter((u) => u.posts.length)).posts;
  return pick(posts);
}

function postDetail(user, data) {
  const res = http.get(`${API}/posts/${somePost(user, data)}`, { ...params(user, 'post_detail'), tags: { endpoint: 'post_detail', name: `${API}/posts/{id}` } });
  check(res, { 'post 200': (r) => r.status === 200 });
}

// 4 in 5 a like or unlike (alternating at random), 1 in 5 a comment: comments are rows that stay.
function interact(user, data) {
  const post = somePost(user, data);
  const tags = { endpoint: 'interact' };
  let res;
  if (Math.random() < 0.8) {
    const method = Math.random() < 0.5 ? 'PUT' : 'DELETE';
    res = http.request(method, `${API}/posts/${post}/like`, null, { ...params(user, 'interact'), tags: { ...tags, name: `${method} ${API}/posts/{id}/like` } });
  } else {
    res = http.post(`${API}/posts/${post}/comments`, JSON.stringify({ body: 'k6 load test comment' }), {
      ...params(user, 'interact'),
      tags: { ...tags, name: `${API}/posts/{id}/comments` },
    });
  }
  check(res, { 'interact 2xx': (r) => r.status >= 200 && r.status < 300 });
}

export function operation(data) {
  const user = data.users[(__VU - 1) % data.users.length];
  let roll = Math.random();
  for (const [share, run] of MIX) {
    if (roll < share) return run(user, data);
    roll -= share;
  }
  return interact(user, data);
}

export function handleSummary(data) {
  return writeSummary(data, {
    scenario: 'mixed',
    mixed: true,
    endpoints: [...TARGETED, ...UNTARGETED],
    iterationsPerSecond: RATE,
    notes: [`${RATE} operations/s before RATE_SCALE: 40 % feed pages, 20 % events, 15 % gigs, 10 % profile, 10 % post, 5 % like or comment`],
  });
}
