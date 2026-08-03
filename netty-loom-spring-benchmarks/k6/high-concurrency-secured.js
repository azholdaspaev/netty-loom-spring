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
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

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
// How long a VU that could not authenticate waits before trying again. Its purpose is to stop a VU
// whose login fails instantly from re-attempting in a hot loop; the plateau it is missing is the
// reason it retries at all rather than idling out the run.
const LOGIN_RETRY_BACKOFF_SECONDS = 1;

// One sample per VU, so the rate reads as the fraction of virtual users that could not authenticate
// and its denominator is VUS. Per attempt it would mean something else entirely: a single VU
// retrying through the plateau would count dozens of times against a tolerance meant to describe the
// population.
const loginFailed = new Rate('login_failed');

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
// Latched the first time this VU finishes an authentication attempt, so login_failed takes the one
// sample per VU its tolerance assumes however many times the VU retries afterwards.
let loginOutcomeRecorded = false;

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
    // Every VU's two session-creating login requests land inside the ramp, so at high VU counts a
    // few can come back as status 0 — a socket that died, not a benchmark that cannot log in.
    // Aborting the run on the first one discarded the other 9,999 VUs' plateau (#111); the line
    // belongs at a failure *rate*. Deliberately not abortOnFail: that would stop the run early and
    // still exit 99, which summarize.py reads as "ran to the end" and would publish. Crossing this
    // fails the run at its end instead, and summarize.py refuses the row (see logins_held there).
    login_failed: ['rate<0.01'],
    // Login latency is measured, not gated — the ramp is the coldest the server ever is, and these
    // are the numbers that say whether a login storm is the thing under strain. They are declared as
    // thresholds because that is the only way to make k6 export a tagged sub-metric at all; without
    // them a run that struggled to authenticate leaves no trace of it in the summary.
    'http_req_duration{phase:login}': ['p(99)<30000'],
    'http_req_failed{phase:login}': ['rate<1'],
    'http_reqs{phase:login}': ['count>0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

/**
 * Sets workParams and returns null once this VU holds a session, or the reason it does not.
 *
 * Only a request that died at the transport level is returned — k6 reports status 0, and a null
 * body, for a socket that timed out or was reset. A login the server actually answered and rejected
 * still aborts the run where it is detected: that is misconfiguration rather than weather, the first
 * one proves the benchmark is broken, and the abort message is the only diagnostic there is, since
 * nothing reads the k6 logs. run-all.sh tolerates the non-zero exit and summarize.py renders the
 * missing export as n/a.
 */
function attemptLogin() {
  const page = http.get(`${BASE_URL}/login`,
    { responseType: 'text', tags: { phase: 'login' }, timeout: '30s' });
  // page.body is null on a status-0 response, and exec() coerces that to the string "null", so a
  // login page that never arrived lands here rather than throwing.
  const token = CSRF_PATTERN.exec(page.body);
  if (!token) {
    if (page.status === 0) {
      return `login page did not complete (${page.error})`;
    }
    exec.test.abort(`no CSRF token on ${BASE_URL}/login (status ${page.status})`);
  }
  // An object body is form-encoded by k6, which is what exercises the servlet parameter path.
  // redirects: 0 so the 302 is observed rather than followed to "/" (no handler there).
  const res = http.post(`${BASE_URL}/login`,
    { username: USERNAME, password: PASSWORD, _csrf: token[1] },
    { redirects: 0, tags: { phase: 'login' }, timeout: '30s' });
  // Checked before the status test below, which reads a dropped POST (status 0) as a rejected login.
  if (res.status === 0) {
    return `login POST did not complete (${res.error})`;
  }
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
  return null;
}

function login() {
  const failure = attemptLogin();
  if (!loginOutcomeRecorded) {
    loginOutcomeRecorded = true;
    loginFailed.add(failure !== null);
    if (failure !== null) {
      // Carries what the abort used to carry, into the same log. One line per VU that failed to
      // authenticate first time, and the tolerance caps how many of those a run can have.
      console.warn(`VU could not authenticate: ${failure}`);
    }
  }
}

export default function () {
  if (workParams === null) {
    login();
  }
  if (workParams === null) {
    // Do not fall through to /work-secured. An unauthenticated request is a 302 answered in
    // microseconds, so one such VU issues thousands a second against an authenticated VU's handful
    // and would swamp the check rate on its own (see the `checks` threshold). Back off and try
    // again instead, so a VU that lost a socket to the login ramp rejoins the plateau.
    sleep(LOGIN_RETRY_BACKOFF_SECONDS);
    return;
  }
  const res = http.get(WORK_URL, workParams);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
