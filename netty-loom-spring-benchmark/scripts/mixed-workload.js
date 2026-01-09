import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

/**
 * Mixed Workload Benchmark: Realistic Traffic Pattern
 *
 * Simulates a realistic mix of different request types:
 * - 40% /hello (minimal overhead baseline)
 * - 30% /json (CPU-bound serialization)
 * - 20% /db (IO-bound blocking)
 * - 10% /mixed (combined CPU + IO)
 *
 * This test represents a more realistic production workload.
 *
 * Usage:
 *   k6 run scripts/mixed-workload.js --env TARGET=http://localhost:8081
 */

// Custom metrics
const errorRate = new Rate('errors');
const helloRequests = new Counter('hello_requests');
const jsonRequests = new Counter('json_requests');
const dbRequests = new Counter('db_requests');
const mixedRequests = new Counter('mixed_requests');

// Configuration from environment
const TARGET = __ENV.TARGET || 'http://localhost:8081';

export const options = {
    scenarios: {
        mixed_workload: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },    // Warm up
                { duration: '1m', target: 200 },    // Ramp up
                { duration: '2m', target: 400 },    // Sustained load
                { duration: '30s', target: 200 },   // Ramp down
                { duration: '30s', target: 0 },     // Cool down
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<1000'],  // 95% under 1s
        errors: ['rate<0.02'],               // Error rate under 2%
    },
};

// Request distribution weights (must sum to 100)
const WEIGHTS = {
    hello: 40,
    json: 30,
    db: 20,
    mixed: 10,
};

function selectEndpoint() {
    const rand = Math.random() * 100;
    if (rand < WEIGHTS.hello) return 'hello';
    if (rand < WEIGHTS.hello + WEIGHTS.json) return 'json';
    if (rand < WEIGHTS.hello + WEIGHTS.json + WEIGHTS.db) return 'db';
    return 'mixed';
}

export default function () {
    const endpoint = selectEndpoint();
    const url = `${TARGET}/${endpoint}`;

    let response;
    let success;

    group(endpoint, function () {
        response = http.get(url, {
            headers: { 'Accept': 'application/json' },
            timeout: '10s',
        });

        switch (endpoint) {
            case 'hello':
                helloRequests.add(1);
                success = check(response, {
                    'hello: status 200': (r) => r.status === 200,
                    'hello: body is Hello World': (r) => r.body === 'Hello World',
                });
                break;

            case 'json':
                jsonRequests.add(1);
                success = check(response, {
                    'json: status 200': (r) => r.status === 200,
                    'json: has timestamp': (r) => {
                        try {
                            return JSON.parse(r.body).timestamp !== undefined;
                        } catch (e) {
                            return false;
                        }
                    },
                });
                break;

            case 'db':
                dbRequests.add(1);
                success = check(response, {
                    'db: status 200': (r) => r.status === 200,
                    'db: has result': (r) => {
                        try {
                            return JSON.parse(r.body).result !== undefined;
                        } catch (e) {
                            return false;
                        }
                    },
                });
                break;

            case 'mixed':
                mixedRequests.add(1);
                success = check(response, {
                    'mixed: status 200': (r) => r.status === 200,
                    'mixed: has computed': (r) => {
                        try {
                            return JSON.parse(r.body).computed !== undefined;
                        } catch (e) {
                            return false;
                        }
                    },
                });
                break;
        }
    });

    errorRate.add(!success);

    // Small sleep for non-blocking endpoints
    if (endpoint === 'hello' || endpoint === 'json') {
        sleep(0.01);
    }
}

export function handleSummary(data) {
    const summary = {
        test: 'mixed-workload',
        target: TARGET,
        timestamp: new Date().toISOString(),
        distribution: WEIGHTS,
        metrics: {
            totalRequests: data.metrics.http_reqs?.values?.count || 0,
            requestsPerSecond: data.metrics.http_reqs?.values?.rate || 0,
            avgDuration: data.metrics.http_req_duration?.values?.avg || 0,
            p95Duration: data.metrics.http_req_duration?.values['p(95)'] || 0,
            p99Duration: data.metrics.http_req_duration?.values['p(99)'] || 0,
            errorRate: data.metrics.errors?.values?.rate || 0,
            breakdown: {
                hello: data.metrics.hello_requests?.values?.count || 0,
                json: data.metrics.json_requests?.values?.count || 0,
                db: data.metrics.db_requests?.values?.count || 0,
                mixed: data.metrics.mixed_requests?.values?.count || 0,
            },
        },
    };

    return {
        stdout: JSON.stringify(summary, null, 2) + '\n',
        'results/mixed-workload-summary.json': JSON.stringify(summary, null, 2),
    };
}
