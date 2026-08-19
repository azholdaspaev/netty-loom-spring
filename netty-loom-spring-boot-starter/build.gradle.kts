plugins {
    `java-library`
    `maven-publish`
}

description = "Spring Boot starter that swaps embedded Tomcat for a Netty server, running every request on a Java 25 virtual thread (Project Loom). No reactive rewrite."

dependencies {
    "springBom"(platform(libs.spring.boot.dependencies))

    api(project(":netty-loom-spring-core"))
    api(project(":netty-loom-spring-mvc"))

    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.web.server)
    api(libs.spring.boot.starter.web) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.starter.restclient)
    testImplementation(libs.spring.boot.resttestclient)

    // spring-boot-starter-test brings Mockito in transitively; load it as an agent.
    "mockitoAgent"(libs.mockito.core)
}

tasks.named<JavaCompile>("compileJava") {
    inputs.files(tasks.named("processResources"))
}
