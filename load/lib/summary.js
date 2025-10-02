// The run file. handleSummary() turns k6's end-of-test data into
// docs/perf/runs/<UTC>-<scenario>-<profile>.json, merged with the environment run.sh recorded before the run
// (commit, uptime, free -m, power, CPU split, row counts). Nothing in it is estimated: every number is k6's
// or the shell's.
import { TARGETS, RATE_SCALE, RAMP, DURATION, scaledRate } from './config.js';

const META = __ENV.RUN_META ? JSON.parse(open(__ENV.RUN_META)) : {};

function round(value) {
  return typeof value === 'number' ? Math.round(value * 100) / 100 : value;
}

function trend(metric) {
  if (!metric) return null;
  const out = {};
  for (const [key, value] of Object.entries(metric.values)) out[key] = round(value);
  return out;
}

function verdicts(metric) {
  if (!metric || !metric.thresholds) return {};
  const out = {};
  for (const [expr, result] of Object.entries(metric.thresholds)) out[expr] = result.ok;
  return out;
}

/** One row per endpoint the scenario measures: the hold phase's latency, failures and target verdict. */
function endpointRows(data, endpoints, spec) {
  const rows = {};
  for (const name of endpoints) {
    const duration = data.metrics[`http_req_duration{endpoint:${name},phase:hold}`];
    const failed = data.metrics[`http_req_failed{endpoint:${name},phase:hold}`];
    const target = TARGETS[name];
    const stats = trend(duration);
    const holdSeconds = DURATION.endsWith('m') ? Number(DURATION.slice(0, -1)) * 60 : Number(DURATION.replace(/s$/, ''));
    rows[name] = {
      target: target ? { rate_per_s: target.rate, p95_ms: target.p95, errors_below: target.errors ?? null } : null,
      asked_rate_per_s: target && !spec.mixed ? round(target.rate * RATE_SCALE) : null,
      requests: stats ? stats.count : 0,
      achieved_rate_per_s: stats && holdSeconds ? round(stats.count / holdSeconds) : null,
      latency_ms: stats,
      failed_rate: failed ? round(failed.values.rate) : null,
      p95_target_met: target && duration ? Object.values(verdicts(duration)).every(Boolean) : null,
      errors_target_met: target && failed && target.errors !== undefined ? Object.values(verdicts(failed)).every(Boolean) : null,
    };
  }
  return rows;
}

export function summarise(data, spec) {
  const { scenario, endpoints, iterationsPerSecond, notes = [] } = spec;
  const metric = (name) => data.metrics[name];
  const checks = metric('checks');
  const thresholds = {};
  for (const [name, m] of Object.entries(data.metrics)) {
    if (m.thresholds) Object.assign(thresholds, Object.fromEntries(Object.entries(verdicts(m)).map(([e, ok]) => [`${name}: ${e}`, ok])));
  }
  const run = {
    schema: 1,
    scenario,
    ...META,
    k6: {
      rate_scale: RATE_SCALE,
      iterations_per_s: scaledRate(iterationsPerSecond),
      ramp: RAMP,
      hold: DURATION,
      test_run_ms: round(data.state.testRunDurationMs),
      iterations: metric('iterations') ? metric('iterations').values.count : 0,
      dropped_iterations: metric('dropped_iterations') ? metric('dropped_iterations').values.count : 0,
      vus_max: metric('vus_max') ? metric('vus_max').values.max : null,
      checks: checks ? { rate: round(checks.values.rate), passes: checks.values.passes, fails: checks.values.fails } : null,
      http_reqs: metric('http_reqs') ? metric('http_reqs').values.count : 0,
      http_req_failed_all: metric('http_req_failed') ? round(metric('http_req_failed').values.rate) : null,
      http_req_duration_all_ms: trend(metric('http_req_duration')),
      data_sent_bytes: metric('data_sent') ? metric('data_sent').values.count : null,
      data_received_bytes: metric('data_received') ? metric('data_received').values.count : null,
    },
    endpoints: endpointRows(data, endpoints, spec),
    thresholds,
    scenario_notes: notes,
  };
  return run;
}

/** handleSummary's return value: the run file (when run.sh names one) and a short line on stdout. */
export function writeSummary(data, spec) {
  const run = summarise(data, spec);
  const lines = [`\n${spec.scenario}: checks ${run.k6.checks ? run.k6.checks.rate : 'n/a'}, dropped ${run.k6.dropped_iterations}`];
  for (const [name, row] of Object.entries(run.endpoints)) {
    const p95 = row.latency_ms ? row.latency_ms['p(95)'] : 'n/a';
    const verdict = row.target ? `target < ${row.target.p95_ms}: ${row.p95_target_met ? 'met' : 'MISSED'}` : 'no target';
    lines.push(`  ${name}: ${row.requests} req in hold, p95 ${p95} ms (${verdict}), failed ${row.failed_rate}`);
  }
  const out = { stdout: `${lines.join('\n')}\n` };
  // setup()'s data (the access tokens) is never written anywhere.
  if (__ENV.RUN_FILE) out[__ENV.RUN_FILE] = `${JSON.stringify(run, null, 2)}\n`;
  return out;
}
