// Signing in the login pool. k6 calls setup() once, before any scenario starts, so the 500 Argon2id
// logins cost CPU before the measured phase and never inside it.
import http from 'k6/http';
import { API, JSON_HEADERS, authHeaders } from './config.js';

// docs/perf/login-pool.txt (medium) or login-pool-small.txt: one handle per line. run.sh passes the file for
// the profile as an absolute path; run by hand, it is relative to this file.
const POOL_FILE = __ENV.LOGIN_POOL || '../../docs/perf/login-pool.txt';
const HANDLES = open(POOL_FILE)
  .split('\n')
  .map((line) => line.trim())
  .filter((line) => line && !line.startsWith('#'));

// The perf dataset's one shared password (lab/src/soundcheck_lab/scale/load.py, local perf stack only);
// run.sh reads it from there, so it is written down in one place.
const PASSWORD = __ENV.PERF_PASSWORD;
const POOL_SIZE = Math.min(HANDLES.length, Number(__ENV.POOL_SIZE || '500'));
// Logins in flight at once: low, so the pinned API isn't swamped by Argon2 while it warms up.
const CONCURRENCY = Number(__ENV.LOGIN_CONCURRENCY || '4');

/** perf_u123 → perf-u123@perf.soundcheck.test. */
export function emailOf(handle) {
  return `${handle.replace(/_/g, '-')}@perf.soundcheck.test`;
}

/** Signs the pool in, CONCURRENCY at a time. Returns [{ handle, userId, token }]; failures are dropped. */
export function loginPool() {
  if (!PASSWORD) {
    throw new Error('PERF_PASSWORD is not set (make load sets it from lab/src/soundcheck_lab/scale/load.py)');
  }
  const users = [];
  let failed = 0;
  for (let i = 0; i < POOL_SIZE; i += CONCURRENCY) {
    const batch = HANDLES.slice(i, Math.min(POOL_SIZE, i + CONCURRENCY)).map((handle) => ({
      method: 'POST',
      url: `${API}/auth/login`,
      body: JSON.stringify({ email: emailOf(handle), password: PASSWORD }),
      params: { headers: JSON_HEADERS, tags: { endpoint: 'login', phase: 'setup' }, timeout: '60s' },
    }));
    const responses = http.batch(batch);
    responses.forEach((res, j) => {
      if (res.status === 200) {
        const body = res.json();
        users.push({ handle: HANDLES[i + j], userId: body.user.id, token: body.accessToken });
      } else {
        failed += 1;
      }
    });
  }
  console.log(`setup: ${users.length} of ${POOL_SIZE} users signed in (${failed} failed)`);
  if (users.length === 0) {
    throw new Error('setup: no user could sign in; is the dataset loaded and the pool file for this profile?');
  }
  return users;
}

/** The user this virtual user acts as: VUs share the pool round-robin. */
export function userFor(users) {
  return users[(__VU - 1) % users.length];
}

/**
 * For each user, the ids of up to `perUser` posts from their first Following page (setup only, CONCURRENCY at
 * a time). Following, not For You: For You's first page was slow on medium before the optimisations, and this is setup.
 */
export function followingPosts(users, perUser = 20) {
  const posts = [];
  for (let i = 0; i < users.length; i += CONCURRENCY) {
    const slice = users.slice(i, i + CONCURRENCY);
    const responses = http.batch(
      slice.map((user) => ({
        method: 'GET',
        url: `${API}/feed/following?limit=${perUser}`,
        params: { headers: authHeaders(user.token), tags: { endpoint: 'following', phase: 'setup' }, timeout: '60s' },
      })),
    );
    responses.forEach((res, j) => {
      const ids = res.status === 200 ? res.json().items.map((post) => post.id) : [];
      posts[i + j] = ids;
    });
  }
  return posts;
}
