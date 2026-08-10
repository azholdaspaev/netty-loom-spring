plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.dependencies))

    api(project(":netty-loom-spring-core"))
    api(project(":netty-loom-spring-mvc"))

    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.web.server)
    api(libs.spring.boot.starter.web) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }

    api(libs.jakarta.servlet.api)

    // annotationProcessor is resolvable and extends nothing, so the api platform above cannot
    // reach it; without its own it would resolve a versionless processor.
    annotationProcessor(platform(libs.spring.boot.dependencies))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.starter.restclient)
    testImplementation(libs.spring.boot.resttestclient)

    // spring-boot-starter-test brings Mockito in transitively; load it as an agent.
    "mockitoAgent"(libs.mockito.core)
}
