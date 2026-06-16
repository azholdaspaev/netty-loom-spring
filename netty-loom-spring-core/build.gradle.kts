plugins {
    `java-library`
}

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
}
