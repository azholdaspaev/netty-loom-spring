// Benchmark module - contains k6 scripts for performance testing
// This module provides Gradle tasks for running benchmarks

// Run a single k6 benchmark script
tasks.register<Exec>("k6Run") {
    group = "benchmark"
    description = "Run a k6 benchmark script. Use -Pscript=<name> and -Ptarget=<url>"

    val script = project.findProperty("script")?.toString() ?: "cpu-bound.js"
    val target = project.findProperty("target")?.toString() ?: "http://localhost:8081"

    workingDir = projectDir
    commandLine("k6", "run", "scripts/$script", "--env", "TARGET=$target")

    doFirst {
        println("Running k6 benchmark: $script against $target")
    }
}

// Run CPU-bound benchmark against a target
tasks.register<Exec>("benchmarkCpuBound") {
    group = "benchmark"
    description = "Run CPU-bound (JSON serialization) benchmark"

    val target = project.findProperty("target")?.toString() ?: "http://localhost:8081"

    workingDir = projectDir
    commandLine("k6", "run", "scripts/cpu-bound.js", "--env", "TARGET=$target")
}

// Run IO-bound benchmark against a target
tasks.register<Exec>("benchmarkIoBound") {
    group = "benchmark"
    description = "Run IO-bound (simulated DB) benchmark"

    val target = project.findProperty("target")?.toString() ?: "http://localhost:8081"

    workingDir = projectDir
    commandLine("k6", "run", "scripts/io-bound.js", "--env", "TARGET=$target")
}

// Run mixed workload benchmark against a target
tasks.register<Exec>("benchmarkMixed") {
    group = "benchmark"
    description = "Run mixed workload benchmark"

    val target = project.findProperty("target")?.toString() ?: "http://localhost:8081"

    workingDir = projectDir
    commandLine("k6", "run", "scripts/mixed-workload.js", "--env", "TARGET=$target")
}

// Run high concurrency benchmark against a target
tasks.register<Exec>("benchmarkHighConcurrency") {
    group = "benchmark"
    description = "Run high concurrency (10K+ VUs) benchmark"

    val target = project.findProperty("target")?.toString() ?: "http://localhost:8081"

    workingDir = projectDir
    commandLine("k6", "run", "scripts/high-concurrency.js", "--env", "TARGET=$target")
}

// Run all benchmarks using the shell script
tasks.register<Exec>("benchmarkAll") {
    group = "benchmark"
    description = "Run full benchmark suite comparing Netty-Loom vs Tomcat"

    dependsOn(":netty-loom-spring-example-netty:bootJar")
    dependsOn(":netty-loom-spring-example-tomcat:bootJar")

    workingDir = projectDir
    commandLine("./run-benchmark.sh")
}

// Run benchmarks against Netty only
tasks.register<Exec>("benchmarkNetty") {
    group = "benchmark"
    description = "Run all benchmarks against Netty-Loom server only"

    dependsOn(":netty-loom-spring-example-netty:bootJar")

    workingDir = projectDir
    commandLine("./run-benchmark.sh", "--netty")
}

// Run benchmarks against Tomcat only
tasks.register<Exec>("benchmarkTomcat") {
    group = "benchmark"
    description = "Run all benchmarks against Tomcat server only"

    dependsOn(":netty-loom-spring-example-tomcat:bootJar")

    workingDir = projectDir
    commandLine("./run-benchmark.sh", "--tomcat")
}

// Help task with instructions
tasks.register("benchmark") {
    group = "benchmark"
    description = "Display benchmark instructions"

    doLast {
        println("""
            |
            |===========================================
            |  Netty-Loom vs Tomcat Benchmark Suite
            |===========================================
            |
            |Prerequisites:
            |  - k6 installed: brew install k6 (macOS) or https://k6.io/docs/getting-started/installation/
            |  - Java 21+ installed
            |
            |Available Gradle tasks:
            |
            |  ./gradlew :netty-loom-spring-benchmark:benchmarkAll
            |      Run full benchmark suite (Netty vs Tomcat comparison)
            |
            |  ./gradlew :netty-loom-spring-benchmark:benchmarkNetty
            |      Run all benchmarks against Netty-Loom only
            |
            |  ./gradlew :netty-loom-spring-benchmark:benchmarkTomcat
            |      Run all benchmarks against Tomcat only
            |
            |  ./gradlew :netty-loom-spring-benchmark:k6Run -Pscript=cpu-bound.js -Ptarget=http://localhost:8081
            |      Run a specific benchmark script against a target
            |
            |Individual benchmark tasks:
            |  - benchmarkCpuBound     (JSON serialization throughput)
            |  - benchmarkIoBound      (Simulated DB call handling)
            |  - benchmarkMixed        (Realistic traffic mix)
            |  - benchmarkHighConcurrency (10K+ connections stress test)
            |
            |Or run directly with the shell script:
            |  ./netty-loom-spring-benchmark/run-benchmark.sh
            |
            |Results are saved to: netty-loom-spring-benchmark/results/
            |
        """.trimMargin())
    }
}
