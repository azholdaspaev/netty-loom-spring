plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.resttestclient)
}
