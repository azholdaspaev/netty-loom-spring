// Scenario 1: low-concurrency throughput.
//
// 1 -> 10 concurrent clients hitting the cheapest possible endpoint (/ping, a constant
// string). This measures raw request-handling overhead and baseline latency with almost
// no work per request, so the three targets are compared on transport cost alone.
//
// Run:  k6 run --env BASE_URL=http://localhost:18080 low-concurrency.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';

export const options = {
  scenarios: {
    low_concurrency: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '5s', target: 1 },   // warmup / JIT
        { duration: '10s', target: 5 },
        { duration: '15s', target: 10 },
      ],
      gracefulStop: '5s',
    },
  },
  thresholds: {
    // Error rate is a hard gate; latency is reported (summaryTrendStats) but never aborts,
    // because measuring it is the whole point.
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<1000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE_URL}/ping`, { timeout: '30s' });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
