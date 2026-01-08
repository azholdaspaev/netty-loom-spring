rootProject.name = "netty-loom-spring"

// Core library modules
include("netty-loom-spring-core")
include("netty-loom-spring-mvc")
include("netty-loom-spring-boot-starter")

// Example applications for benchmarking
include("netty-loom-spring-example-netty")
include("netty-loom-spring-example-tomcat")

// Benchmark module (k6 scripts)
include("netty-loom-spring-benchmark")
