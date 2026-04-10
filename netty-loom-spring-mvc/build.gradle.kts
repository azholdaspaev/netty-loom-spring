plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":netty-loom-spring-core"))
    api(libs.spring.web)
    api(libs.spring.webmvc)
}
