plugins {
    java
}

// Version catalog
val nettyVersion = "4.1.116.Final"
val springBootVersion = "3.4.1"
val springVersion = "6.2.1"
val jakartaServletVersion = "6.0.0"
val micrometerVersion = "1.12.1"
val junitVersion = "5.10.1"
val assertjVersion = "3.24.2"
val mockitoVersion = "5.8.0"

allprojects {
    group = "io.github.azholdaspaev.nettyloom"
    version = "0.0.1-SNAPSHOT"

    // Store versions in extra properties for subprojects
    extra["nettyVersion"] = nettyVersion
    extra["springBootVersion"] = springBootVersion
    extra["springVersion"] = springVersion
    extra["jakartaServletVersion"] = jakartaServletVersion
    extra["micrometerVersion"] = micrometerVersion
    extra["junitVersion"] = junitVersion
    extra["assertjVersion"] = assertjVersion
    extra["mockitoVersion"] = mockitoVersion
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
        testImplementation("org.assertj:assertj-core:$assertjVersion")
        testImplementation("org.mockito:mockito-core:$mockitoVersion")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
