plugins {
    java
}

allprojects {
    group = "io.github.azholdaspaev.nettyloom"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
    }
}
