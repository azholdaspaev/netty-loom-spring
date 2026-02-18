plugins {
    `java-library`
}

dependencies {
    api(project(":netty-loom-spring-core"))
    api(project(":netty-loom-spring-mvc"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation(libs.jakarta.servlet.api)

    annotationProcessor(libs.spring.boot.configuration.processor)

    compileOnly(libs.micrometer.core)
    compileOnly(libs.micrometer.tracing)

    testImplementation(libs.spring.boot.starter.test)
}
