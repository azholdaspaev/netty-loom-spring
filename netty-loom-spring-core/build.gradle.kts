plugins {
    `java-library`
}

dependencies {
    api(libs.netty.transport)
    api(libs.netty.codec.http)
    api(libs.netty.handler)

    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
}
