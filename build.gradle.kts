import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.maven.publish) apply false
}

val springBootVersion = libs.versions.spring.boot.get()

subprojects {
    apply(plugin = "java-library")

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

    // A dependencyScope rather than `api(platform(...))`: an api platform lands in apiElements and
    // runtimeElements, which are the variants Gradle publishes, so consumers inherit the whole Boot
    // constraint set and see unrelated parts of their graph rewritten (kafka-clients 3.6.0 -> 4.1.2).
    // Nothing derives a variant from a dependencyScope, so it stays out of the publication. #30
    val springBom = configurations.dependencyScope("springBom")
    listOf(
        "compileClasspath",
        "runtimeClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
        "annotationProcessor",
    ).forEach { configurations.getByName(it).extendsFrom(springBom.get()) }

    pluginManager.withPlugin("io.spring.dependency-management") {
        configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
            }
        }
    }

    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            pom {
                url = "https://github.com/azholdaspaev/netty-loom-spring"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "azholdaspaev"
                        name = "Adil Zholdaspaev"
                        email = "adilzholdaspaev@gmail.com"
                        url = "https://github.com/azholdaspaev"
                    }
                }
                scm {
                    url = "https://github.com/azholdaspaev/netty-loom-spring"
                    connection = "scm:git:https://github.com/azholdaspaev/netty-loom-spring.git"
                    developerConnection = "scm:git:ssh://git@github.com/azholdaspaev/netty-loom-spring.git"
                }
            }
        }

        // Dependencies are declared versionless against the Boot BOM, so without this the POM and
        // the .module both publish empty versions and no consumer can resolve them. #147
        configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                versionMapping {
                    allVariants { fromResolutionResult() }
                }
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
