import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * IO-Bound Benchmark: Simulated Database Call Handling
 *
 * Tests the /db endpoint which simulates a 100ms blocking database call.
 * This measures how well the server handles blocking IO operations.
 *
 * Key insight: With virtual threads (Netty-Loom), blocking IO should not
 * consume platform threads, allowing much higher concurrency.
 *
 * Usage:
 *   k6 run scripts/io-bound.js --env TARGET=http://localhost:8081
 */

// Custom metrics
const errorRate = new Rate('errors');
const requestDuration = new Trend('request_duration');
const dbLatency = new Trend('db_latency');

// Configuration from environment
const TARGET = __ENV.TARGET || 'http://localhost:8081';

export const options = {
    scenarios: {
        io_bound_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 100 },   // Ramp up to 100 VUs
                { duration: '1m', target: 500 },    // Ramp up to 500 VUs
                { duration: '1m', target: 1000 },   // Peak at 1000 VUs
                { duration: '30s', target: 500 },   // Ramp down
                { duration: '30s', target: 0 },     // Ramp down to 0
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<300'],   // 95% under 300ms (100ms DB + overhead)
        errors: ['rate<0.01'],               // Error rate under 1%
        db_latency: ['avg>90', 'avg<150'],   // DB latency should be ~100ms
    },
};

export default function () {
    const url = `${TARGET}/db`;

    const response = http.get(url, {
        headers: {
            'Accept': 'application/json',
        },
        timeout: '5s',
    });

    // Record custom metrics
    requestDuration.add(response.timings.duration);

    // Validate response and extract DB latency
    const success = check(response, {
        'status is 200': (r) => r.status === 200,
        'response has result': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.result !== undefined;
            } catch (e) {
                return false;
            }
        },
        'latency is reported': (r) => {
            try {
                const body = JSON.parse(r.body);
                if (body.latency) {
                    dbLatency.add(body.latency);
                    return true;
                }
                return false;
            } catch (e) {
                return false;
            }
        },
    });

    errorRate.add(!success);

    // No sleep needed - the 100ms DB call provides natural pacing
}

export function handleSummary(data) {
    const summary = {
        test: 'io-bound',
        target: TARGET,
        timestamp: new Date().toISOString(),
        metrics: {
            requests: data.metrics.http_reqs?.values?.count || 0,
            requestsPerSecond: data.metrics.http_reqs?.values?.rate || 0,
            avgDuration: data.metrics.http_req_duration?.values?.avg || 0,
            p95Duration: data.metrics.http_req_duration?.values['p(95)'] || 0,
            p99Duration: data.metrics.http_req_duration?.values['p(99)'] || 0,
            avgDbLatency: data.metrics.db_latency?.values?.avg || 0,
            errorRate: data.metrics.errors?.values?.rate || 0,
        },
    };

    return {
        stdout: JSON.stringify(summary, null, 2) + '\n',
        'results/io-bound-summary.json': JSON.stringify(summary, null, 2),
    };
}
