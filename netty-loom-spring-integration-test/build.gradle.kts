dependencies {
    "springBom"(platform(libs.spring.boot.dependencies))

    testImplementation(project(":netty-loom-spring-boot-starter"))
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.starter.security)
    testImplementation(libs.spring.boot.starter.security.oauth2.resource.server)
    testImplementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.resttestclient)

    // spring-boot-starter-test brings Mockito in transitively; load it as an agent.
    "mockitoAgent"(libs.mockito.core)
}
