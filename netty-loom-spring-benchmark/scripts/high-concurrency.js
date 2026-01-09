import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

/**
 * High Concurrency Benchmark: 10K+ Connections Stress Test
 *
 * Tests the server's ability to handle extreme concurrency levels.
 * Uses the /hello endpoint for minimal processing overhead to focus
 * on connection handling capacity.
 *
 * Key insight: Virtual threads should allow handling 10K+ concurrent
 * requests without exhausting the thread pool (unlike traditional Tomcat).
 *
 * Usage:
 *   k6 run scripts/high-concurrency.js --env TARGET=http://localhost:8081
 */

// Custom metrics
const errorRate = new Rate('errors');
const connectionErrors = new Counter('connection_errors');
const timeoutErrors = new Counter('timeout_errors');
const requestDuration = new Trend('request_duration');

// Configuration from environment
const TARGET = __ENV.TARGET || 'http://localhost:8081';

export const options = {
    scenarios: {
        high_concurrency: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 100 },    // Warm up
                { duration: '30s', target: 1000 },   // 1K VUs
                { duration: '1m', target: 5000 },    // 5K VUs
                { duration: '1m', target: 10000 },   // Peak: 10K VUs
                { duration: '30s', target: 5000 },   // Ramp down
                { duration: '30s', target: 0 },      // Cool down
            ],
            gracefulRampDown: '30s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<2000'],  // 95% under 2s (allowing for queueing)
        errors: ['rate<0.05'],               // Error rate under 5%
    },
    // Increase connection limits for high concurrency
    batch: 50,
    batchPerHost: 50,
};

export default function () {
    const url = `${TARGET}/hello`;

    let response;
    try {
        response = http.get(url, {
            timeout: '10s',
        });
    } catch (e) {
        connectionErrors.add(1);
        errorRate.add(true);
        return;
    }

    // Record request duration
    requestDuration.add(response.timings.duration);

    // Check for timeout errors
    if (response.error_code === 1050) { // k6 timeout error code
        timeoutErrors.add(1);
        errorRate.add(true);
        return;
    }

    // Validate response
    const success = check(response, {
        'status is 200': (r) => r.status === 200,
        'body is Hello World': (r) => r.body === 'Hello World',
    });

    errorRate.add(!success);

    // Tiny sleep to prevent complete CPU saturation on client
    sleep(0.001);
}

export function handleSummary(data) {
    const summary = {
        test: 'high-concurrency',
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
            errorRate: data.metrics.errors?.values?.rate || 0,
            connectionErrors: data.metrics.connection_errors?.values?.count || 0,
            timeoutErrors: data.metrics.timeout_errors?.values?.count || 0,
        },
    };

    return {
        stdout: JSON.stringify(summary, null, 2) + '\n',
        'results/high-concurrency-summary.json': JSON.stringify(summary, null, 2),
    };
}
