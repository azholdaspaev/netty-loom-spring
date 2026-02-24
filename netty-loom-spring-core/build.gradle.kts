plugins {
    `java-library`
}

dependencies {
    api(libs.netty.handler)
    api(libs.netty.codec.http)
    api(libs.slf4j.api)

    runtimeOnly(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
    runtimeOnly(variantOf(libs.netty.transport.native.epoll) { classifier("linux-aarch_64") })
    runtimeOnly(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-x86_64") })
    runtimeOnly(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-aarch_64") })

    testImplementation(libs.archunit.junit5)
}
