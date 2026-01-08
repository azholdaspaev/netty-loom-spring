plugins {
    id("java-library")
}

dependencies {
    // Project modules
    api(project(":netty-loom-spring-core"))
    api(project(":netty-loom-spring-mvc"))

    // Spring Boot auto-configuration
    implementation("org.springframework.boot:spring-boot-autoconfigure:${rootProject.extra["springBootVersion"]}")

    // Optional: Actuator for health indicators
    compileOnly("org.springframework.boot:spring-boot-starter-actuator:${rootProject.extra["springBootVersion"]}")

    // Optional: Micrometer for metrics
    compileOnly("io.micrometer:micrometer-core:${rootProject.extra["micrometerVersion"]}")

    // Annotation processor for configuration metadata
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:${rootProject.extra["springBootVersion"]}")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test:${rootProject.extra["springBootVersion"]}")
    testImplementation("org.springframework.boot:spring-boot-starter-web:${rootProject.extra["springBootVersion"]}") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
}
