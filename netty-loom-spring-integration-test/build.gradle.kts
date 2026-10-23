dependencies {
    "springBom"(platform(libs.spring.boot.dependencies))

    implementation(project(":netty-loom-spring-boot-starter"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    implementation(libs.micrometer.tracing.bridge.otel)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.opentelemetry.sdk.testing)

    // spring-boot-starter-test brings Mockito in transitively; load it as an agent.
    "mockitoAgent"(libs.mockito.core)
}
