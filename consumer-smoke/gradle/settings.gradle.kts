rootProject.name = "consumer-smoke-gradle"

pluginManagement {
    plugins {
        id("org.springframework.boot") version providers.gradleProperty("smokeBootVersion").get()
    }
}
