plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":netty-loom-spring-core"))
    api(project(":netty-loom-spring-mvc"))

    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.web.server)
    api(libs.spring.boot.starter.web) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }

    implementation(libs.jakarta.servlet.api)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.starter.restclient)
    testImplementation(libs.spring.boot.resttestclient)
}
