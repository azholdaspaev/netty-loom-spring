plugins {
    `java-library`
    `maven-publish`
}

description = "Netty HTTP server with a pluggable pipeline SPI, running every request on a Java 25 virtual thread (Project Loom). No Spring dependency."

dependencies {
    api(libs.netty.transport)
    api(libs.netty.codec.http)
    api(libs.netty.handler)

    implementation(libs.netty.transport.native.epoll)
    implementation(libs.netty.transport.native.kqueue)

    runtimeOnly(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
    runtimeOnly(variantOf(libs.netty.transport.native.epoll) { classifier("linux-aarch_64") })
    runtimeOnly(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-x86_64") })
    runtimeOnly(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-aarch_64") })

    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.slf4j.simple)
}
