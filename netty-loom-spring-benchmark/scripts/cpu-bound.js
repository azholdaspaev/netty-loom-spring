import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * CPU-Bound Benchmark: JSON Serialization Throughput
 *
 * Tests the /json endpoint which performs JSON object serialization.
 * This measures CPU-bound performance without blocking IO.
 *
 * Usage:
 *   k6 run scripts/cpu-bound.js --env TARGET=http://localhost:8081
 */

// Custom metrics
const errorRate = new Rate('errors');
const requestDuration = new Trend('request_duration');

// Configuration from environment
const TARGET = __ENV.TARGET || 'http://localhost:8081';

export const options = {
    scenarios: {
        cpu_bound_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },   // Ramp up to 50 VUs
                { duration: '1m', target: 100 },   // Ramp up to 100 VUs
                { duration: '1m', target: 200 },   // Peak at 200 VUs
                { duration: '30s', target: 100 },  // Ramp down
                { duration: '30s', target: 0 },    // Ramp down to 0
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<500'],  // 95% of requests should be under 500ms
        errors: ['rate<0.01'],              // Error rate should be under 1%
    },
};

export default function () {
    const url = `${TARGET}/json`;

    const response = http.get(url, {
        headers: {
            'Accept': 'application/json',
        },
    });

    // Record custom metrics
    requestDuration.add(response.timings.duration);

    // Validate response
    const success = check(response, {
        'status is 200': (r) => r.status === 200,
        'response has timestamp': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.timestamp !== undefined;
            } catch (e) {
                return false;
            }
        },
        'response has data array': (r) => {
            try {
                const body = JSON.parse(r.body);
                return Array.isArray(body.data);
            } catch (e) {
                return false;
            }
        },
    });

    errorRate.add(!success);

    // Small sleep to prevent overwhelming the system
    sleep(0.01);
}

export function handleSummary(data) {
    const summary = {
        test: 'cpu-bound',
        target: TARGET,
        timestamp: new Date().toISOString(),
        metrics: {
            requests: data.metrics.http_reqs?.values?.count || 0,
            requestsPerSecond: data.metrics.http_reqs?.values?.rate || 0,
            avgDuration: data.metrics.http_req_duration?.values?.avg || 0,
            p95Duration: data.metrics.http_req_duration?.values['p(95)'] || 0,
            p99Duration: data.metrics.http_req_duration?.values['p(99)'] || 0,
            errorRate: data.metrics.errors?.values?.rate || 0,
        },
    };

    return {
        stdout: JSON.stringify(summary, null, 2) + '\n',
        'results/cpu-bound-summary.json': JSON.stringify(summary, null, 2),
    };
}
