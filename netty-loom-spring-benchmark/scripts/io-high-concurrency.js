import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

/**
 * IO-Bound + High Concurrency Benchmark: Ultimate Stress Test
 *
 * Combines IO-bound workload (100ms blocking DB call) with extreme concurrency (10K VUs).
 * This is the ultimate test for virtual threads - massive concurrent blocking IO.
 *
 * With 10K VUs each blocking for 100ms:
 * - Tomcat (200 threads): Max theoretical = 2,000 RPS, massive queuing
 * - Virtual threads: Should handle all 10K concurrently = ~100K RPS theoretical
 *
 * Usage:
 *   k6 run scripts/io-high-concurrency.js --env TARGET=http://localhost:8081
 */

// Custom metrics
const errorRate = new Rate('errors');
const connectionErrors = new Counter('connection_errors');
const timeoutErrors = new Counter('timeout_errors');
const requestDuration = new Trend('request_duration');
const dbLatency = new Trend('db_latency');

// Configuration from environment
const TARGET = __ENV.TARGET || 'http://localhost:8081';

export const options = {
    scenarios: {
        io_high_concurrency: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 500 },     // Warm up
                { duration: '30s', target: 2000 },    // 2K VUs
                { duration: '1m', target: 5000 },     // 5K VUs
                { duration: '1m', target: 10000 },    // Peak: 10K VUs with IO
                { duration: '30s', target: 5000 },    // Ramp down
                { duration: '30s', target: 0 },       // Cool down
            ],
            gracefulRampDown: '30s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<5000'],  // 95% under 5s (allowing for extreme queueing)
        errors: ['rate<0.10'],               // Error rate under 10% (stress test)
    },
    batch: 50,
    batchPerHost: 50,
};

export default function () {
    const url = `${TARGET}/db`;

    let response;
    try {
        response = http.get(url, {
            headers: {
                'Accept': 'application/json',
            },
            timeout: '30s',  // Longer timeout for high load
        });
    } catch (e) {
        connectionErrors.add(1);
        errorRate.add(true);
        return;
    }

    // Record request duration
    requestDuration.add(response.timings.duration);

    // Check for timeout errors
    if (response.error_code === 1050) {
        timeoutErrors.add(1);
        errorRate.add(true);
        return;
    }

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
    });

    // Extract DB latency if available
    try {
        const body = JSON.parse(response.body);
        if (body.latency) {
            dbLatency.add(body.latency);
        }
    } catch (e) {
        // Ignore parse errors
    }

    errorRate.add(!success);

    // Tiny sleep to prevent client-side saturation
    sleep(0.001);
}

export function handleSummary(data) {
    const summary = {
        test: 'io-high-concurrency',
        target: TARGET,
        timestamp: new Date().toISOString(),
        peakVUs: 10000,
        metrics: {
            requests: data.metrics.http_reqs?.values?.count || 0,
            requestsPerSecond: data.metrics.http_reqs?.values?.rate || 0,
            avgDuration: data.metrics.http_req_duration?.values?.avg || 0,
            p95Duration: data.metrics.http_req_duration?.values['p(95)'] || 0,
            p99Duration: data.metrics.http_req_duration?.values['p(99)'] || 0,
            maxDuration: data.metrics.http_req_duration?.values?.max || 0,
            avgDbLatency: data.metrics.db_latency?.values?.avg || 0,
            errorRate: data.metrics.errors?.values?.rate || 0,
            connectionErrors: data.metrics.connection_errors?.values?.count || 0,
            timeoutErrors: data.metrics.timeout_errors?.values?.count || 0,
        },
    };

    return {
        stdout: JSON.stringify(summary, null, 2) + '\n',
        'results/io-high-concurrency-summary.json': JSON.stringify(summary, null, 2),
    };
}
