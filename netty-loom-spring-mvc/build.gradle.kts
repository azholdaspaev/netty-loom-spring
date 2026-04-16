plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":netty-loom-spring-core"))

    implementation(libs.spring.web)
    implementation(libs.spring.webmvc)
    implementation(libs.spring.context)
    implementation(libs.jakarta.servlet.api)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
}
