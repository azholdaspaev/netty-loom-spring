    // Benchmark module - contains k6 scripts only, no Java code
// This module is for organization purposes

tasks.register("benchmark") {
    group = "verification"
    description = "Runs the k6 benchmark suite"

    doLast {
        println("Run benchmarks with: ./run-benchmark.sh")
        println("Requires k6 to be installed: https://k6.io/docs/getting-started/installation/")
    }
}
