// Scenario 2: high-concurrency with blocking I/O — the Project Loom win.
//
// VUS concurrent clients each loop GET /work, where the controller does Thread.sleep(50)
// to simulate a blocking 50ms database call. With keep-alive ON, 1 VU holds 1 persistent
// TCP connection and has exactly 1 request in flight at a time, so:
//
//     VU count  ==  concurrent open connections  ==  concurrent blocked requests
//
// That mapping is what makes the server-side memory-per-connection measurement meaningful
// (see scripts/sample-memory.sh). It also exposes the thread-per-request ceiling: Tomcat's
// default platform-thread pool tops out at ~200 workers, so at thousands of VUs requests
// queue (p99 climbs) and excess connections are refused (error rate climbs). Virtual-thread
// targets (Netty-Loom, Tomcat+VT) park cheaply instead.
//
// Run:  k6 run --env BASE_URL=http://localhost:18080 --env VUS=10000 high-concurrency.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const VUS = parseInt(__ENV.VUS || '10000', 10);
const RAMP = __ENV.RAMP || '15s';        // warmup ramp, trimmed when interpreting steady state
const DURATION = __ENV.DURATION || '60s'; // steady-state plateau at VUS connections

export const options = {
  discardResponseBodies: true, // keep the k6 client lean at high VU counts
  scenarios: {
    high_concurrency: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },     // ramp up to N connections (warmup)
        { duration: DURATION, target: VUS },  // hold N connections (measure here)
        { duration: '5s', target: 0 },        // ramp down
      ],
      gracefulStop: '10s',
    },
  },
  thresholds: {
    // Hard gate on errors; p99 reported and given a generous ceiling so a saturated
    // platform-thread target is recorded as "slow + erroring" (the finding) rather than
    // crashing the harness. k6 exits non-zero on breach; run-all.sh tolerates that.
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<10000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE_URL}/work`, { timeout: '30s' });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
