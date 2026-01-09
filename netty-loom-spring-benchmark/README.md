# Netty-Loom vs Tomcat Benchmark Suite

Performance comparison benchmarks using [k6](https://k6.io/) to measure the difference between
Netty-Loom (with Java virtual threads) and traditional Tomcat servers.

## Prerequisites

- **k6** - Modern load testing tool
  - macOS: `brew install k6`
  - Linux: See [k6 installation docs](https://k6.io/docs/getting-started/installation/)
  - Docker: `docker pull grafana/k6`

- **Java 21+** - Required for virtual threads support
  - Verify: `java -version`

- **Gradle** - Provided via wrapper (`./gradlew`)

## Quick Start

### Using Gradle (Recommended)

```bash
# Display help and available tasks
./gradlew :netty-loom-spring-benchmark:benchmark

# Run full benchmark suite (compares Netty vs Tomcat)
./gradlew :netty-loom-spring-benchmark:benchmarkAll

# Run benchmarks against Netty-Loom only
./gradlew :netty-loom-spring-benchmark:benchmarkNetty

# Run benchmarks against Tomcat only
./gradlew :netty-loom-spring-benchmark:benchmarkTomcat

# Run a specific benchmark
./gradlew :netty-loom-spring-benchmark:benchmarkCpuBound -Ptarget=http://localhost:8081
```

### Using Shell Script

```bash
# Run full benchmark suite
./netty-loom-spring-benchmark/run-benchmark.sh

# Run against Netty only
./netty-loom-spring-benchmark/run-benchmark.sh --netty

# Run specific script only
./netty-loom-spring-benchmark/run-benchmark.sh --script cpu-bound
```

### Running k6 Directly

```bash
# Start an example server first
java -jar netty-loom-spring-example-netty/build/libs/netty-loom-spring-example-netty.jar &

# Run a benchmark
k6 run netty-loom-spring-benchmark/scripts/cpu-bound.js --env TARGET=http://localhost:8081
```

## Benchmark Scripts

| Script | Endpoint | Description | VUs |
|--------|----------|-------------|-----|
| `cpu-bound.js` | `/json` | JSON serialization throughput | 10 → 200 |
| `io-bound.js` | `/db` | Blocking IO handling (100ms delay) | 50 → 1000 |
| `mixed-workload.js` | All | Realistic traffic mix | 50 → 400 |
| `high-concurrency.js` | `/hello` | Stress test with 10K+ connections | 100 → 10000 |

### Workload Details

#### CPU-Bound (`cpu-bound.js`)
Tests JSON serialization performance. Each request returns a moderately complex JSON object.
Measures pure CPU throughput without IO blocking.

#### IO-Bound (`io-bound.js`)
Tests blocking IO handling. Each request simulates a 100ms database call using `Thread.sleep()`.
This is where virtual threads shine - they allow many more concurrent blocked requests.

#### Mixed Workload (`mixed-workload.js`)
Simulates realistic production traffic:
- 40% `/hello` - Minimal overhead baseline
- 30% `/json` - CPU-bound serialization
- 20% `/db` - IO-bound blocking
- 10% `/mixed` - Combined CPU + IO

#### High Concurrency (`high-concurrency.js`)
Stress test ramping up to 10,000+ concurrent virtual users.
Uses `/hello` endpoint to minimize processing overhead and focus on connection handling.

## Configuration

### Environment Variables

All scripts accept the `TARGET` environment variable:

```bash
k6 run scripts/cpu-bound.js --env TARGET=http://localhost:8081
```

### Customizing Scripts

Each script exports `options` that can be modified:

```javascript
export const options = {
    scenarios: {
        my_scenario: {
            executor: 'ramping-vus',
            stages: [
                { duration: '1m', target: 100 },  // Ramp to 100 VUs
                { duration: '2m', target: 100 },  // Stay at 100
                { duration: '1m', target: 0 },    // Ramp down
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<500'],
        errors: ['rate<0.01'],
    },
};
```

## Results

Results are saved to `netty-loom-spring-benchmark/results/`:

```
results/
├── netty/
│   ├── cpu-bound-summary.json
│   ├── cpu-bound-results.json
│   ├── io-bound-summary.json
│   └── ...
├── tomcat/
│   ├── cpu-bound-summary.json
│   └── ...
└── benchmark-report.txt
```

### Interpreting Results

Key metrics to compare:

| Metric | Description | Better |
|--------|-------------|--------|
| `http_reqs.rate` | Requests per second | Higher |
| `http_req_duration.avg` | Average response time | Lower |
| `http_req_duration.p95` | 95th percentile latency | Lower |
| `errors.rate` | Error rate | Lower |

Expected differences:
- **CPU-bound**: Similar performance (both process on platform threads)
- **IO-bound**: Netty-Loom significantly better (virtual threads don't block)
- **High concurrency**: Netty-Loom handles more connections (no thread exhaustion)

## Docker Support

### Using Docker Compose

```bash
cd netty-loom-spring-benchmark

# Build JARs first
../gradlew :netty-loom-spring-example-netty:bootJar
../gradlew :netty-loom-spring-example-tomcat:bootJar

# Start both servers
docker compose up -d netty-app tomcat-app

# Run k6 in container
docker compose --profile benchmark run k6 run /scripts/cpu-bound.js --env TARGET=http://host.docker.internal:8081

# Stop servers
docker compose down
```

## Troubleshooting

### k6 not found
Install k6: `brew install k6` (macOS) or see [installation docs](https://k6.io/docs/getting-started/installation/)

### Connection refused
Ensure the target server is running:
```bash
curl http://localhost:8081/hello
```

### Port already in use
Check for running processes:
```bash
lsof -i :8081
lsof -i :8082
```

### Out of file descriptors (high concurrency)
Increase limits:
```bash
ulimit -n 65536
```

## Architecture Notes

### Why Virtual Threads Matter

Traditional Tomcat uses a thread pool (default 200 threads). Each blocking operation (DB call, HTTP request) holds a platform thread. Under high concurrency, the thread pool is exhausted.

Netty-Loom with virtual threads:
- Virtual threads are cheap (~1KB vs ~1MB for platform threads)
- Blocking operations unmount the virtual thread, freeing the carrier thread
- Can handle 10K+ concurrent blocking operations

### Expected Results

| Scenario | Tomcat | Netty-Loom | Improvement |
|----------|--------|------------|-------------|
| IO-bound (1000 VUs) | Limited by 200 threads | Handles all 1000 | 5x+ throughput |
| High concurrency (10K) | Failures/timeouts | Successful | Handles load |

## License

MIT License - see project root for details.
