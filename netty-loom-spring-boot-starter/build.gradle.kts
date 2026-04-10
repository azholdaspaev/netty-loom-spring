plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":netty-loom-spring-mvc"))
    api(libs.spring.boot.autoconfigure)
}
