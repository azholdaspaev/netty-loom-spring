plugins {
    `java-library`
}

dependencies {
    // Platform rather than io.spring.dependency-management: the plugin writes no versions into
    // published Gradle module metadata, leaving consumers unable to resolve. #147
    api(platform(libs.spring.boot.dependencies))

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
