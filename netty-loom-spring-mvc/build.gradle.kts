plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

mavenPublishing {
    pom {
        name = "netty-loom-spring-mvc"
        description = "Servlet bridge that runs Spring MVC's DispatcherServlet on a Netty HTTP server, one Java 25 virtual thread (Project Loom) per request."
    }
}

dependencies {
    "springBom"(platform(libs.spring.boot.dependencies))

    api(project(":netty-loom-spring-core"))

    // api, not implementation: both are on this module's public ABI, so consumers must compile
    // against them. #148
    api(libs.spring.webmvc)
    api(libs.jakarta.servlet.api)

    implementation(libs.spring.web)
    implementation(libs.spring.context)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.slf4j.simple)
}
