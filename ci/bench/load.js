// BENCH ONLY (never merged): the same load on the native binary and on the JVM run of the same application.
// Phases: warm-up (excluded), steady open-loop rate for STEADY minutes (latency), then a closed loop of MAX_VUS
// virtual users for 3 minutes (throughput). The asks are the hook's gate question (ostrom-e2e load/lib.js `ask`).
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.OSTROM_URL || 'http://127.0.0.1:8080';
const TOKEN = __ENV.OSTROM_LOAD_TOKEN;
const STEADY = Number(__ENV.STEADY_MINUTES || 12);
const RATE = Number(__ENV.ASK_RATE || 200);
const MAX_VUS = Number(__ENV.MAX_VUS || 64);
const H = { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}` };
const ACTIONS = [
  { class: 'test.run', command: 'npm test' },
  { class: 'git.push', command: 'git push origin feat/x', ref: 'refs/heads/feat/x' },
  { class: 'deploy', command: 'helm upgrade api ./chart --dry-run' },
  { class: 'fs.delete', command: 'rm -rf build' },
];
function sha(n) {
  let s = ''; let x = n >>> 0;
  while (s.length < 40) { x = (Math.imul(x ^ (x >>> 15), 2246822507) + 0x9e3779b9) >>> 0; s += x.toString(16).padStart(8, '0'); }
  return s.slice(0, 40);
}
const big = '1000000s';
const trend = ['p(50)<1000000', 'p(95)<1000000', 'p(99)<1000000'];
export const options = {
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warm: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '2m', preAllocatedVUs: 50, maxVUs: 400, exec: 'mix' },
    steady: { executor: 'constant-arrival-rate', startTime: '2m', rate: RATE, timeUnit: '1s', duration: `${STEADY}m`, preAllocatedVUs: 50, maxVUs: 400, exec: 'mix' },
    max: { executor: 'constant-vus', startTime: `${STEADY + 2}m`, vus: MAX_VUS, duration: '3m', exec: 'mix' },
  },
  thresholds: {
    'http_req_duration{scenario:steady}': trend,
    'http_req_duration{scenario:steady,name:ask}': trend,
    'http_req_duration{scenario:steady,name:read}': trend,
    'http_req_duration{scenario:max}': trend,
    'http_reqs{scenario:steady}': ['count>0'],
    'http_reqs{scenario:max}': ['count>0'],
    'http_req_failed{scenario:steady}': ['rate<1'],
    'http_req_failed{scenario:max}': ['rate<1'],
    'dropped_iterations': ['count<100000000'],
  },
};
// 8 in 10 iterations ask a gate (a write and the rules), 1 reads the instance key, 1 lists the person's teams.
export function mix() {
  const i = __VU * 1000000 + __ITER;
  const k = i % 10;
  if (k === 8) {
    const r = http.get(`${BASE}/api/v1/instance`, { tags: { name: 'read' } });
    check(r, { 'instance 200': (x) => x.status === 200 });
  } else if (k === 9) {
    const r = http.get(`${BASE}/api/v1/orgs`, { headers: H, tags: { name: 'read' } });
    check(r, { 'orgs 200': (x) => x.status === 200 });
  } else {
    const a = ACTIONS[i % ACTIONS.length];
    const body = { repo: 'acme/api', class: a.class, tool: 'Bash', command: a.command };
    if (a.class === 'git.push') body.push = { remote: 'origin', refs: [{ ref: a.ref, sha: sha(i) }] };
    const r = http.post(`${BASE}/api/v1/gates`, JSON.stringify(body), { headers: H, tags: { name: 'ask' } });
    check(r, { 'ask 200': (x) => x.status === 200 });
  }
}
export function handleSummary(data) {
  return { [__ENV.SUMMARY_FILE || 'summary.json']: JSON.stringify(data, null, 1) };
}
