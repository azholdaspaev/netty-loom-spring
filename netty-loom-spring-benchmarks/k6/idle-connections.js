// Idle connections: the per-connection half of scenario 2's memory figure.
//
// VUS clients each open one keep-alive connection and then leave it idle, so the server holds
// VUS connections with roughly no request in flight. Against scenario 2, where every connection
// also carries one blocked request, the difference is the per-in-flight-request cost.
//
// A GET /ping every KEEPALIVE_S seconds keeps the connection open through every target's idle
// timeout (Netty-Loom's server.netty.read-timeout is 30s, Tomcat's keep-alive timeout 20s) and
// stays under Tomcat's 100 requests per connection over a 60s hold.
//
// Run:  k6 run --env BASE_URL=http://localhost:18080 --env VUS=10000 idle-connections.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const VUS = parseInt(__ENV.VUS || '10000', 10);
const RAMP = __ENV.RAMP || '15s';
const DURATION = __ENV.DURATION || '60s';
const KEEPALIVE_S = parseInt(__ENV.KEEPALIVE_S || '10', 10);

export const options = {
  discardResponseBodies: true,
  noVUConnectionReuse: false,
  scenarios: {
    idle_connections: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '5s', target: 0 },
      ],
      gracefulStop: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE_URL}/ping`, { timeout: '30s' });
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(KEEPALIVE_S);
}
