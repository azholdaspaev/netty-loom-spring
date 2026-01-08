dependencies {
    // Netty (for Servlet adapters)
    implementation("io.netty:netty-all:${rootProject.extra["nettyVersion"]}")

    // Servlet API
    implementation("jakarta.servlet:jakarta.servlet-api:${rootProject.extra["jakartaServletVersion"]}")

    // Spring WebMVC
    implementation("org.springframework:spring-webmvc:${rootProject.extra["springVersion"]}")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test:${rootProject.extra["springBootVersion"]}")
}
