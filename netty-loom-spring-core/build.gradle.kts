dependencies {
    // Netty for HTTP server
    implementation("io.netty:netty-all:${rootProject.extra["nettyVersion"]}")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.4.14")
}
