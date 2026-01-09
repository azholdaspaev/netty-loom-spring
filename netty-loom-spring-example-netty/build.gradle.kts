plugins {
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    // Use Netty-Loom starter
    implementation(project(":netty-loom-spring-boot-starter"))

    // Spring Web without Tomcat
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }

    // Actuator for health and metrics
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Jackson for JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Enable bootJar for creating executable JAR
tasks.bootJar {
    enabled = true
}

tasks.jar {
    enabled = false
}
