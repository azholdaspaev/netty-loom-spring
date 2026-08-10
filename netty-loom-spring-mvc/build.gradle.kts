plugins {
    `java-library`
}

dependencies {
    // Platform rather than io.spring.dependency-management: the plugin writes no versions into
    // published Gradle module metadata, leaving consumers unable to resolve. #147
    api(platform(libs.spring.boot.dependencies))

    api(project(":netty-loom-spring-core"))

    implementation(libs.spring.web)
    implementation(libs.spring.webmvc)
    implementation(libs.spring.context)
    implementation(libs.jakarta.servlet.api)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.slf4j.simple)
}
