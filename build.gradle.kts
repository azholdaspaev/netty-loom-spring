import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    alias(libs.plugins.spring.dependency.management) apply false
}

val springBootVersion = libs.versions.spring.boot.get()

subprojects {
    apply(plugin = "java-library")

    group = "io.github.azholdaspaev"
    version = "0.1.0-SNAPSHOT"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    // Mockito must be loaded as a -javaagent: self-attaching is deprecated on the JDK 25
    // toolchain and will be disallowed outright in a future release. Declared for every
    // subproject but left empty by default — modules whose tests pull in Mockito opt in with
    // `"mockitoAgent"(libs.mockito.core)`. An empty configuration contributes no jvm arg.
    val mockitoAgent = configurations.dependencyScope("mockitoAgent")
    val mockitoAgentClasspath = configurations.resolvable("mockitoAgentClasspath") {
        extendsFrom(mockitoAgent.get())
        isTransitive = false
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        // Netty's epoll/kqueue transports call System::loadLibrary from the unnamed module.
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        jvmArgumentProviders.add(CommandLineArgumentProvider {
            mockitoAgentClasspath.get().files.map { "-javaagent:$it" }
        })

        testLogging {
            showStandardStreams = true
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    pluginManager.withPlugin("io.spring.dependency-management") {
        configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
            }
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }
}
