dependencies {
    // Netty for HTTP server
    implementation("io.netty:netty-all:${rootProject.extra["nettyVersion"]}")

    // Spring Boot for WebServer interface
    implementation("org.springframework.boot:spring-boot:${rootProject.extra["springBootVersion"]}")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test:${rootProject.extra["springBootVersion"]}")
}
