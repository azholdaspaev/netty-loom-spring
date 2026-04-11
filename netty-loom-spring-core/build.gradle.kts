plugins {
    `java-library`
}

dependencies {
    api(libs.netty.transport)
    api(libs.netty.codec.http)
    api(libs.netty.handler)

    testImplementation(libs.junit.jupiter)
}
