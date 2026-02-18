plugins {
    `java-library`
}

dependencies {
    api(project(":netty-loom-spring-core"))

    implementation(libs.spring.web)
    implementation(libs.spring.webmvc)
    implementation(libs.spring.context)
    implementation(libs.jakarta.servlet.api)

    compileOnly(libs.spring.security.web)
    compileOnly(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
}
