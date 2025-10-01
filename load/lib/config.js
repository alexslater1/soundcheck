// Shared settings for every k6 scenario. `make load` (ops/perf/run.sh) sets these
// through the environment; run by hand, the defaults point at the perf API on the measurement port.

export const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8200').replace(/\/$/, '');

// The local stack's API (:8080) and edge (:8088) are never load-tested: refuse before a single request is sent.
const port = (BASE_URL.match(/:(\d+)(?:\/|$)/) || [])[1];
if (port === '8080' || port === '8088') {
  throw new Error(`BASE_URL ${BASE_URL} is the local stack (8080/8088); load tests run only against the perf API`);
}

export const API = `${BASE_URL}/api/v1`;

// Every target rate is multiplied by this (0.1 for a gentle smoke run, 1.0 for the target rates).
export const RATE_SCALE = Number(__ENV.RATE_SCALE || '1.0');
// The ramp reaches the target rate in RAMP; the hold then keeps it for DURATION. Only the hold is measured.
export const RAMP = __ENV.RAMP || '1m';
export const DURATION = __ENV.DURATION || '1m';
// Requests wait this long before k6 counts them failed. For You's first page was far slower on medium
// before the optimisations, so this is generous.
export const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '120s';
// Upper bound on concurrent virtual users per scenario. When the API is slow, arrival-rate executors add
// VUs to keep the rate; past this cap k6 drops iterations and counts them (dropped_iterations).
export const MAX_VUS = Number(__ENV.MAX_VUS || '600');

// The API accepts only its two clients' names; the load stands in for the phone app.
export const JSON_HEADERS = { 'Content-Type': 'application/json', 'X-Client': 'android' };

// The trend statistics every run file records.
export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'];

// The latency targets, measured on medium. A scenario's thresholds are built from these, so a run's
// verdict is the target's verdict. abortOnFail stays false: a missed target still yields its numbers.
export const TARGETS = {
  feed_first: { rate: 50, p95: 250, errors: 0.001 },
  feed_later: { rate: 150, p95: 40 },
  following: { rate: 100, p95: 60 },
  gigs_near: { rate: 100, p95: 80 },
  events: { rate: 100, p95: 40 },
  upload_create: { rate: 20, p95: 100 },
};

/**
 * Endpoints a scenario records without a target (mixed.js's profile, post and like/comment): k6 keeps
 * a tag's numbers only for metrics with a threshold, so these get ones that always pass.
 */
export function recordOnly(endpoints) {
  const thresholds = {};
  for (const name of endpoints) {
    thresholds[`http_req_duration{endpoint:${name},phase:hold}`] = ['max>=0'];
    thresholds[`http_req_failed{endpoint:${name},phase:hold}`] = ['rate>=0'];
  }
  return thresholds;
}

/** Thresholds for the named endpoints, over the hold phase only (the ramp is warm-up). */
export function thresholdsFor(endpoints) {
  const thresholds = {
    // Script health, not a target: a scenario is accepted when ≥ 99 % of its checks pass.
    checks: [{ threshold: 'rate>0.99', abortOnFail: false }],
  };
  for (const name of endpoints) {
    const target = TARGETS[name];
    thresholds[`http_req_duration{endpoint:${name},phase:hold}`] = [
      { threshold: `p(95)<${target.p95}`, abortOnFail: false },
    ];
    // Recorded for every endpoint (so the run file has it); a target only where one is set.
    thresholds[`http_req_failed{endpoint:${name},phase:hold}`] = [
      { threshold: `rate<${target.errors === undefined ? 1 : target.errors}`, abortOnFail: false },
    ];
  }
  return thresholds;
}

/**
 * Two arrival-rate scenarios that run one after the other: `ramp` climbs from 0 to the target in RAMP,
 * then `hold` keeps it constant for DURATION. Arrival-rate (open model): k6 starts iterations at the rate
 * whatever the latency, so a slow API shows up as latency and dropped iterations, never as a lower rate.
 */
export function arrivalScenarios(exec, iterationsPerSecond, { seconds = 1, tags: extraTags = {} } = {}) {
  const rate = scaledRate(iterationsPerSecond);
  // `seconds` is how long one iteration usually takes (think time included): rate × seconds VUs are busy.
  const preAllocated = Math.min(MAX_VUS, Math.max(5, Math.ceil(rate * seconds * 1.5)));
  return {
    [`${exec}_ramp`]: {
      executor: 'ramping-arrival-rate',
      exec,
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: preAllocated,
      maxVUs: MAX_VUS,
      stages: [{ target: rate, duration: RAMP }],
      tags: { phase: 'ramp', ...extraTags },
    },
    [`${exec}_hold`]: {
      executor: 'constant-arrival-rate',
      exec,
      startTime: RAMP,
      rate,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: preAllocated,
      maxVUs: MAX_VUS,
      tags: { phase: 'hold', ...extraTags },
    },
  };
}

/** The rate a scenario actually asked for, after RATE_SCALE (recorded in the run file). */
export function scaledRate(iterationsPerSecond) {
  return Math.max(1, Math.round(iterationsPerSecond * RATE_SCALE));
}

export function authHeaders(token) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
