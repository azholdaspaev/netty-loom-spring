plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(libs.spring.web)
    api(libs.spring.webmvc)

    implementation(libs.jakarta.servlet.api)

    testImplementation(libs.junit.jupiter)
}
