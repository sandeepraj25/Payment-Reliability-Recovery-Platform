#!/usr/bin/env node
// PayGuard load test — zero dependencies (Node >= 20: built-in fetch + crypto).
//
// Drives the REAL running stack (API process + separate worker + PostgreSQL + RabbitMQ) and reports
// latency percentiles and throughput for four workloads, then checks correctness invariants.
//
// The run FAILS (exit 1) only when a correctness invariant breaks (duplicate fulfilment, lost event,
// server error, extra order). It never fails on speed: we do not invent performance targets.
// Numbers are only meaningful for the machine they were measured on (see the "environment" block).
//
// Usage:
//   BASE_URL=http://localhost:8080 ADMIN_USER=admin ADMIN_PASSWORD=... WEBHOOK_SECRET=... node loadtest/run.mjs
// Optional sizes: CHECKOUTS, CHECKOUT_CONCURRENCY, PIPELINES, PIPELINE_CONCURRENCY, WEBHOOKS,
//                 WEBHOOK_CONCURRENCY, WEBHOOK_DUP_RATIO, STORM_KEYS, STORM_COPIES, OUT (results file)
//
// NOTE: run with the rate limiter disabled (PAYGUARD_RATE_LIMIT_ENABLED=false). From a single client IP
// the limiter (50 req/s API, 200 req/s webhooks) would otherwise be what we measure.

import { createHmac, randomUUID } from 'node:crypto';
import { writeFileSync, appendFileSync } from 'node:fs';
import os from 'node:os';

const env = (k, d) => process.env[k] ?? d;
const num = (k, d) => Number(env(k, d));

const BASE = env('BASE_URL', 'http://localhost:8080');
const AUTH = 'Basic ' + Buffer.from(`${env('ADMIN_USER', 'admin')}:${env('ADMIN_PASSWORD', '')}`).toString('base64');
const SECRET = env('WEBHOOK_SECRET', '');
const CFG = {
  checkouts: num('CHECKOUTS', 400), checkoutConcurrency: num('CHECKOUT_CONCURRENCY', 20),
  pipelines: num('PIPELINES', 200), pipelineConcurrency: num('PIPELINE_CONCURRENCY', 20),
  webhooks: num('WEBHOOKS', 2000), webhookConcurrency: num('WEBHOOK_CONCURRENCY', 40),
  webhookDupRatio: num('WEBHOOK_DUP_RATIO', 0.2),
  stormKeys: num('STORM_KEYS', 50), stormCopies: num('STORM_COPIES', 6),
  redeliverEvery: 4, redeliverTimes: 3, pollMs: 100, fulfilTimeoutMs: 60_000,
};
const RUN = Date.now().toString(36);   // makes ids unique across runs against the same database

// ------------------------------------------------------------------ helpers

// `noAuth`: webhooks are authenticated by HMAC, not by login. Sending Basic credentials there would make Spring
// Security run a BCrypt check (deliberately slow, tens of ms of CPU) on every call and distort the measurement.
async function call(method, path, { body, headers = {}, raw = false, noAuth = false } = {}) {
  const t0 = performance.now();
  let status = 0, text = '', error = null, retryAfter = null;
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: { ...(noAuth ? {} : { Authorization: AUTH }),
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers },
      body: body === undefined ? undefined : raw ? body : JSON.stringify(body),
    });
    status = res.status;
    retryAfter = res.headers.get('retry-after');
    text = await res.text();
  } catch (e) {
    error = e.cause?.code ?? e.message;   // transport error (connection refused/reset), not an HTTP status
  }
  const ms = performance.now() - t0;
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON body */ }
  return { status, json, text, ms, error, retryAfter };
}

/** Runs `tasks` (array of async fns) with at most `limit` in flight. Returns results in order. */
async function pool(tasks, limit) {
  const results = new Array(tasks.length);
  let next = 0;
  async function lane() {
    while (next < tasks.length) {
      const i = next++;
      results[i] = await tasks[i]();
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, tasks.length) }, lane));
  return results;
}

/** Nearest-rank percentile. */
function pct(sorted, p) {
  if (sorted.length === 0) return null;
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

function stats(name, samples, wallMs, extra = {}) {
  const lat = samples.map((s) => s.ms).sort((a, b) => a - b);
  const codes = {};
  for (const s of samples) {
    const k = s.error ? `transport:${s.error}`
      : s.status >= 400 && s.json?.code ? `${s.status}:${s.json.code}` : String(s.status);
    codes[k] = (codes[k] ?? 0) + 1;
  }
  const r = (x) => (x == null ? null : Math.round(x * 10) / 10);
  return {
    name, requests: samples.length, wallSeconds: r(wallMs / 1000),
    throughputPerSec: r(samples.length / (wallMs / 1000)),
    latencyMs: { p50: r(pct(lat, 50)), p95: r(pct(lat, 95)), p99: r(pct(lat, 99)), max: r(lat.at(-1)) },
    statusCodes: codes, ...extra,
  };
}

const tally = (xs) => xs.reduce((m, x) => ((m[x] = (m[x] ?? 0) + 1), m), {});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const checkoutBody = (i) => ({ customerRef: `load_${i}`, amountMinor: 499900, currency: 'INR' });

const MAX_CHECKOUT_ATTEMPTS = 10;
/**
 * A client that follows the documented API contract: 409 (same key still in progress) and 503 (temporarily
 * unavailable, with Retry-After) mean "retry with the SAME Idempotency-Key". Each retry waits
 * max(Retry-After, exponential backoff with jitter). Returns the first attempt (latency of a single request),
 * the final attempt, the total time until the final answer and every intermediate status seen.
 */
async function checkoutWithRetry(key, body) {
  const t0 = performance.now();
  const seen = [];
  let first = null, last = null;
  for (let attempt = 1; attempt <= MAX_CHECKOUT_ATTEMPTS; attempt++) {
    last = await call('POST', '/api/checkout', { body, headers: { 'Idempotency-Key': key } });
    first ??= last;
    seen.push(last.error ? `transport:${last.error}` : last.status >= 400 && last.json?.code ? `${last.status}:${last.json.code}` : String(last.status));
    if (last.error || (last.status !== 409 && last.status !== 503)) break;
    const retryAfterMs = (Number(last.retryAfter) || 0) * 1000;   // header in seconds; absent -> backoff only
    const backoff = Math.min(4000, 100 * 2 ** (attempt - 1)) * (0.5 + Math.random());
    await sleep(Math.max(retryAfterMs, backoff));
  }
  return { first, last, seen, totalMs: performance.now() - t0 };
}

const failures = [];
function invariant(ok, message) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
  if (!ok) failures.push(message);
}

// ------------------------------------------------------------------ workloads

/** 1. Checkout API: unique Idempotency-Keys, measures the idempotent create path incl. the gateway HTTP call. */
async function checkoutLoad() {
  const t0 = performance.now();
  const runs = await pool(Array.from({ length: CFG.checkouts }, (_, i) => () =>
    checkoutWithRetry(`load-${RUN}-${i}`, checkoutBody(i))), CFG.checkoutConcurrency);
  const wall = performance.now() - t0;
  const firsts = runs.map((r) => r.first);
  const created = runs.filter((r) => r.last.status === 201).map((r) => r.last.json);
  return { report: stats('checkout (unique keys), first attempt', firsts, wall,
    { concurrency: CFG.checkoutConcurrency, retriedRequests: runs.filter((r) => r.seen.length > 1).length,
      intermediateResponses: tally(runs.flatMap((r) => r.seen.slice(0, -1))) }), created, runs };
}

/** 2. Retry storm: STORM_KEYS keys, each sent STORM_COPIES times at the same moment. Must create one order per key. */
async function retryStorm() {
  const tasks = [];
  for (let k = 0; k < CFG.stormKeys; k++) {
    for (let c = 0; c < CFG.stormCopies; c++) {
      tasks.push(() => checkoutWithRetry(`storm-${RUN}-${k}`, checkoutBody(`storm_${k}`)));
    }
  }
  const t0 = performance.now();
  const runs = await Promise.all(tasks.map((t) => t()));   // all first attempts in flight at once
  const wall = performance.now() - t0;
  // Every request must END with 201, and all requests for one key must carry the same orderId.
  const byKey = new Map();
  runs.forEach((r, i) => {
    const key = Math.floor(i / CFG.stormCopies);
    if (!byKey.has(key)) byKey.set(key, new Set());
    byKey.get(key).add(r.last.status === 201 ? r.last.json?.orderId : `not-201:${r.last.status}`);
  });
  const conflictingKeys = [...byKey.values()].filter((set) => set.size > 1).length;
  const resolve = runs.map((r) => ({ ms: r.totalMs, status: r.last.status, error: r.last.error }));
  return {
    report: stats('checkout retry storm (same key ×' + CFG.stormCopies + '), first attempt', runs.map((r) => r.first), wall,
      { keys: CFG.stormKeys, keysWithDifferentOrderIds: conflictingKeys,
        intermediateResponses: tally(runs.flatMap((r) => r.seen.slice(0, -1))) }),
    resolveReport: stats('checkout retry storm, time until final answer (client retries per contract)', resolve, wall),
    runs, conflictingKeys,
  };
}

/**
 * 3. End-to-end pipeline: authorize + capture at the gateway, then wait until the merchant order is FULFILLED.
 * Path measured: gateway commit → signed webhook → ingest TX (event + outbox) → outbox poll (500 ms interval)
 * → RabbitMQ → worker in a SEPARATE process → payment update + fulfilment. Every 4th order also gets its
 * capture webhook redelivered 3× afterwards (duplicate delivery under load).
 */
async function pipelineLoad(orders) {
  const capture = [], e2e = [], redeliveries = [];
  let timeouts = 0;
  const t0 = performance.now();
  await pool(orders.map((o, i) => async () => {
    const auth = await call('POST', `/sim/gateway/payments/${o.gatewayPaymentId}/authorize`);
    if (auth.status !== 200) { capture.push(auth); return; }
    const cap = await call('POST', `/sim/gateway/payments/${o.gatewayPaymentId}/capture`);
    capture.push(cap);
    if (cap.status !== 200) return;
    const start = performance.now();
    for (;;) {
      const r = await call('GET', `/api/orders/${o.orderId}`);
      if (r.json?.status === 'FULFILLED') { e2e.push({ ms: performance.now() - start, status: 200 }); break; }
      if (performance.now() - start > CFG.fulfilTimeoutMs) { timeouts++; break; }
      await sleep(CFG.pollMs);
    }
    if (i % CFG.redeliverEvery === 0) {
      const evs = await call('GET', `/sim/gateway/payments/${o.gatewayPaymentId}/events`);
      const captured = (evs.json ?? []).find((e) => e.eventType === 'payment.captured');
      for (let n = 0; captured && n < CFG.redeliverTimes; n++) {
        redeliveries.push(await call('POST', `/sim/gateway/events/${captured.id}/redeliver`));
      }
    }
  }), CFG.pipelineConcurrency);
  const wall = performance.now() - t0;
  return {
    capture: stats('gateway capture (incl. AFTER_COMMIT webhook hand-off)', capture, wall),
    e2e: stats('capture → order FULFILLED (end to end)', e2e, wall,
      { concurrency: CFG.pipelineConcurrency, pollIntervalMs: CFG.pollMs, timeouts,
        note: 'includes up to 500 ms outbox poll wait; ±100 ms polling granularity' }),
    redelivery: stats('duplicate webhook redelivery', redeliveries, wall),
    timeouts, fulfilled: e2e.length, redeliveries,
  };
}

/** 4. Raw webhook ingestion: signed events of an unknown type (stored as IGNORED: no business side effects). */
async function webhookLoad() {
  const unique = Math.round(CFG.webhooks * (1 - CFG.webhookDupRatio));
  const ids = [];
  for (let i = 0; i < CFG.webhooks; i++) {
    ids.push(i < unique ? `evt_load_${RUN}_${i}` : `evt_load_${RUN}_${Math.floor(Math.random() * unique)}`);
  }
  // Shuffle so duplicates interleave with first deliveries (some duplicates race their original).
  for (let i = ids.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [ids[i], ids[j]] = [ids[j], ids[i]]; }
  // A real gateway resends the IDENTICAL body (it only re-signs with a fresh timestamp), so build each body once.
  const bodies = new Map();
  const bodyFor = (id) => {
    if (!bodies.has(id)) bodies.set(id, JSON.stringify({ id, type: 'loadtest.ping', created_at: new Date().toISOString() }));
    return bodies.get(id);
  };
  const t0 = performance.now();
  const samples = await pool(ids.map((id) => () => {
    const body = bodyFor(id);
    const t = Math.floor(Date.now() / 1000);
    const sig = createHmac('sha256', SECRET).update(`${t}.${body}`).digest('hex');
    return call('POST', '/api/webhooks/payguard-sim', {
      body, raw: true, noAuth: true, headers: { 'PayGuard-Signature': `t=${t},v1=${sig}` } });
  }), CFG.webhookConcurrency);
  const wall = performance.now() - t0;
  const results = {};
  for (const s of samples) { const k = s.json?.result ?? 'none'; results[k] = (results[k] ?? 0) + 1; }
  const distinct = new Set(ids).size;
  return { report: stats('webhook ingest (HMAC verify + durable insert + commit)', samples, wall,
    { concurrency: CFG.webhookConcurrency, distinctEventIds: distinct, results }), distinct, results, samples };
}

// ------------------------------------------------------------------ main

async function main() {
  if (!SECRET || AUTH.endsWith(Buffer.from(':').toString('base64'))) {
    console.error('Set ADMIN_PASSWORD and WEBHOOK_SECRET'); process.exit(2);
  }
  const before = (await call('GET', '/api/ops/summary')).json;
  if (!before) { console.error('cannot reach /api/ops/summary at ' + BASE); process.exit(2); }
  const ordersBefore = Object.values(before.ordersByStatus ?? {}).reduce((a, b) => a + b, 0);
  const fulfilmentsBefore = before.fulfilments ?? 0;

  console.log('1/4 checkout load…');      const co = await checkoutLoad();
  console.log('2/4 retry storm…');        const storm = await retryStorm();
  console.log('3/4 end-to-end pipeline…');
  const pipe = await pipelineLoad(co.created.filter((o) => o?.gatewayPaymentId).slice(0, CFG.pipelines));
  console.log('4/4 webhook ingest…');     const wh = await webhookLoad();

  // Let the asynchronous side drain (outbox → RabbitMQ → worker) before checking invariants.
  let after;
  for (let i = 0; i < 60; i++) {
    after = (await call('GET', '/api/ops/summary')).json;
    const inFlight = (after.webhookEventsByStatus?.RECEIVED ?? 0) + (after.webhookEventsByStatus?.RETRY_SCHEDULED ?? 0)
      + (after.webhookEventsByStatus?.PROCESSING ?? 0);
    if ((after.outboxBacklog ?? 0) === 0 && inFlight === 0) break;
    await sleep(1000);
  }

  console.log('\n--- invariants');
  // 503 (with Retry-After) and 409 are documented "retry with the same key" signals. Anything else 5xx, or a
  // transport error, is unexpected. Every intermediate response ever seen is checked, not only final ones.
  const RETRY_SIGNALS = new Set(['409:IDEMPOTENCY_REQUEST_IN_PROGRESS', '503:GATEWAY_UNAVAILABLE', '503:TEMPORARILY_UNAVAILABLE']);
  const checkoutSeen = [...co.runs, ...storm.runs].flatMap((r) => r.seen);
  const other = [...wh.samples, ...pipe.redeliveries];
  const unexpected = checkoutSeen.filter((x) => x.startsWith('transport:') || (/^5/.test(x) && !RETRY_SIGNALS.has(x)))
    .concat(other.filter((s) => s.error || s.status >= 500).map((s) => s.error ?? String(s.status)));
  invariant(unexpected.length === 0,
    `no unexpected server errors (500/502) and no transport errors in any workload${unexpected.length ? ': ' + JSON.stringify(tally(unexpected)) : ''}`);
  invariant(checkoutSeen.every((x) => x === '201' || RETRY_SIGNALS.has(x)),
    'checkout only ever answered 201 or a documented retry signal (409 in progress / 503 with Retry-After)');
  invariant(co.created.length === CFG.checkouts, `every unique-key checkout ended in 201: ${co.created.length}/${CFG.checkouts}`);
  invariant(storm.runs.every((r) => r.last.status === 201),
    `retry storm: every one of ${storm.runs.length} requests ended in 201 by retrying with its key`);
  invariant(storm.conflictingKeys === 0, 'retry storm: all requests for one key got the same orderId');
  const ordersAfter = Object.values(after.ordersByStatus ?? {}).reduce((a, b) => a + b, 0);
  invariant(ordersAfter - ordersBefore === CFG.checkouts + CFG.stormKeys,
    `orders created = unique keys (${CFG.checkouts} + ${CFG.stormKeys}); observed ${ordersAfter - ordersBefore}`);
  invariant(pipe.timeouts === 0, `every captured order reached FULFILLED within ${CFG.fulfilTimeoutMs / 1000}s (timeouts: ${pipe.timeouts})`);
  invariant(after.fulfilments - fulfilmentsBefore === pipe.fulfilled,
    `exactly one fulfilment per captured order despite ${pipe.redeliveries.length} duplicate redeliveries: ` +
    `${after.fulfilments - fulfilmentsBefore} fulfilments for ${pipe.fulfilled} orders`);
  invariant(pipe.redeliveries.every((r) => r.json?.httpStatus === 200), 'every redelivered duplicate was acknowledged 200 by the merchant');
  invariant((wh.results.IGNORED ?? 0) === wh.distinct,
    `webhook ingest stored each distinct event exactly once: ${wh.results.IGNORED ?? 0} first deliveries for ${wh.distinct} distinct ids`);
  invariant((wh.results.DUPLICATE ?? 0) === CFG.webhooks - wh.distinct,
    `every repeated delivery was recognised as DUPLICATE: ${wh.results.DUPLICATE ?? 0}/${CFG.webhooks - wh.distinct}`);
  invariant((after.failedEvents ?? 0) === (before.failedEvents ?? 0), 'no event was dead-lettered during the run');
  invariant((after.outboxBacklog ?? 0) === 0, 'outbox fully drained after the run');

  const result = {
    measuredAt: new Date().toISOString(),
    environment: { node: process.version, platform: `${os.platform()} ${os.release()}`, cpus: os.cpus().length,
      cpuModel: os.cpus()[0]?.model, memoryGb: Math.round(os.totalmem() / 2 ** 30),
      note: env('LOADTEST_ENV_NOTE', 'load generator, API, worker, PostgreSQL and RabbitMQ share this machine') },
    config: CFG,
    workloads: [co.report, storm.report, storm.resolveReport, pipe.capture, pipe.e2e, pipe.redelivery, wh.report],
    invariants: { passed: failures.length === 0, failures },
  };

  console.log('\n--- results');
  console.log(JSON.stringify(result, null, 2));
  writeFileSync(env('OUT', 'loadtest-results.json'), JSON.stringify(result, null, 2));

  if (process.env.GITHUB_STEP_SUMMARY) {
    const rows = result.workloads.map((w) =>
      `| ${w.name} | ${w.requests} | ${w.throughputPerSec} | ${w.latencyMs.p50} | ${w.latencyMs.p95} | ${w.latencyMs.p99} | ${w.latencyMs.max} | ${JSON.stringify(w.statusCodes)} |`);
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, [
      '## PayGuard load test', '', `Environment: ${JSON.stringify(result.environment)}`, '',
      '| workload | requests | req/s | p50 ms | p95 ms | p99 ms | max ms | status codes |', '|---|---|---|---|---|---|---|---|',
      ...rows, '', `Invariants: **${failures.length === 0 ? 'ALL PASSED' : 'FAILED: ' + failures.join('; ')}**`, ''].join('\n'));
  }
  process.exit(failures.length === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
