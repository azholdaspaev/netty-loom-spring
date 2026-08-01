// Scenario 3: high-concurrency blocking I/O behind the Spring Security filter chain.
//
// Mirrors high-concurrency.js exactly except for the endpoint: /work-secured does the same
// Thread.sleep(50), but sits behind http.formLogin(). Each VU authenticates once and then loops the
// protected endpoint on the session id it holds in module scope (see below — the cookie jar cannot
// be used for this), so VU count still equals concurrent connections equals concurrent blocked
// requests — and the scenario-2 vs scenario-3 delta on the same target and run is what the chain
// costs.
//
// /ping and /work stay outside the chain (BenchmarkSecurityConfig scopes securityMatcher to the
// secured paths), so scenarios 1 and 2 remain comparable to the pre-Security snapshot.
//
// Run:  k6 run --env BASE_URL=http://localhost:18080 --env VUS=10000 high-concurrency-secured.js
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const VUS = parseInt(__ENV.VUS || '10000', 10);
const RAMP = __ENV.RAMP || '15s';        // warmup ramp, and where every VU's login lands
const DURATION = __ENV.DURATION || '60s'; // steady-state plateau at VUS connections
const USERNAME = __ENV.USERNAME || 'bench';
const PASSWORD = __ENV.PASSWORD || 'bench';

// Spring Security renders the hidden field as
//     <input name="_csrf" type="hidden" value="..." />
// so the value attribute does NOT immediately follow the name attribute. [^>]* is load-bearing;
// without it this silently matches nothing and every VU aborts. Mirrors the pattern asserted by
// BenchmarkControllerTest in both example modules.
const CSRF_PATTERN = /name="_csrf"[^>]*value="([^"]+)"/;

const WORK_URL = `${BASE_URL}/work-secured`;

// Module scope is per-VU in k6 (one JS runtime per VU), so this holds the authenticated session for
// the VU's whole lifetime rather than one iteration. The session id is replayed as an explicit
// Cookie header because **k6 resets the default cookie jar at the start of every iteration** —
// relying on the jar authenticates iteration 1 and silently drops every later request back to
// /login. Verified, not assumed.
//
// Built once per VU rather than per iteration: at 10k VUs the steady state is the measured path, and
// rebuilding the header, tag and params objects every request would charge scenario 3 client-side
// allocations that scenario 2 never pays — landing in the published Δ as Security's cost.
let workParams = null;

export const options = {
  discardResponseBodies: true, // keep the client lean at high VU counts; the login GET opts back in
  scenarios: {
    high_concurrency_secured: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },      // ramp up to N connections (warmup + all logins)
        { duration: DURATION, target: VUS },  // hold N connections (measure here)
        { duration: '5s', target: 0 },        // ramp down
      ],
      gracefulStop: '10s',
    },
  },
  thresholds: {
    // Tagged sub-metrics, not the bare ones: each VU's two login requests are issued during the
    // ramp, when the server is coldest, and would otherwise land in the same trend as the plateau
    // while inflating http_reqs. Declaring a threshold on a tagged selector is also the only way to
    // make k6 emit that sub-metric into --summary-export, which is what summarize.py reads.
    'http_req_failed{phase:work}': ['rate<0.01'],
    'http_req_duration{phase:work}': ['p(99)<10000'],
    'http_reqs{phase:work}': ['count>0'],
    // The check below is a correctness gate, not a nicety. An unauthenticated steady state turns
    // every request into a cheap 302 back to /login: higher throughput, lower latency and a 0%
    // transport error rate — a fake win that reads exactly like a real one. The gate only works
    // because the work request sets redirects: 0; with k6's default redirect following, the check
    // would see the login page's own 200 and pass at 100% while measuring nothing.
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function login() {
  const page = http.get(`${BASE_URL}/login`,
    { responseType: 'text', tags: { phase: 'login' }, timeout: '30s' });
  const token = CSRF_PATTERN.exec(page.body);
  if (!token) {
    // Abort the run rather than fail the iteration: a VU that cannot authenticate would otherwise
    // retry the login on every iteration for the whole plateau, and a benchmark that cannot log in
    // has nothing to report. run-all.sh tolerates the non-zero exit and summarize.py renders the
    // missing export as n/a.
    exec.test.abort(`no CSRF token on ${BASE_URL}/login (status ${page.status})`);
  }
  // An object body is form-encoded by k6, which is what exercises the servlet parameter path.
  // redirects: 0 so the 302 is observed rather than followed to "/" (no handler there).
  const res = http.post(`${BASE_URL}/login`,
    { username: USERNAME, password: PASSWORD, _csrf: token[1] },
    { redirects: 0, tags: { phase: 'login' }, timeout: '30s' });
  // A failed login also answers 302 (to /login?error), so the status alone proves nothing. The
  // Location header is what distinguishes the two.
  if (res.status !== 302 || String(res.headers['Location']).includes('error')) {
    exec.test.abort(
      `login POST returned ${res.status} -> ${res.headers['Location']}, expected 302 -> /`);
  }
  // Spring Security rotates the session id on login (session fixation, CWE-384), so the id to carry
  // is the one on this response, not the one the login page issued.
  const cookie = res.cookies['JSESSIONID'];
  if (!cookie) {
    exec.test.abort('login POST set no JSESSIONID cookie');
  }
  workParams = {
    headers: { Cookie: `JSESSIONID=${cookie[0].value}` },
    redirects: 0, // see the `checks` threshold above
    tags: { phase: 'work' },
    timeout: '30s',
  };
}

export default function () {
  if (workParams === null) {
    login();
  }
  const res = http.get(WORK_URL, workParams);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
