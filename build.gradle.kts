plugins {
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotless)
}

val springBootVersion = libs.versions.spring.boot.get()
val palantirJavaFormatVersion = libs.versions.palantir.java.format.get()
val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val assertjCore = libs.assertj.core
val mockitoCore = libs.mockito.core
val mockitoJunitJupiter = libs.mockito.junit.jupiter

allprojects {
    group = "io.github.azholdaspaev.nettyloom"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "io.spring.dependency-management")

    pluginManager.withPlugin("io.spring.dependency-management") {
        configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
            }
        }
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(24))
            }
        }

        tasks.withType<JavaCompile> {
            options.compilerArgs.add("--enable-preview")
        }

        tasks.withType<Test> {
            useJUnitPlatform()
            jvmArgs("--enable-preview")
            jvmArgs("-Dio.netty.leakDetectionLevel=paranoid")
        }

        tasks.withType<Javadoc> {
            val opts = options as StandardJavadocDocletOptions
            opts.addStringOption("-enable-preview", "")
            opts.addStringOption("source", "24")
        }

        dependencies {
            val testImplementation by configurations
            val testRuntimeOnly by configurations

            testImplementation(platform(junitBom))
            testImplementation(junitJupiter)
            testImplementation(assertjCore)
            testImplementation(mockitoCore)
            testImplementation(mockitoJunitJupiter)
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

spotless {
    java {
        target("*/src/**/*.java")
        palantirJavaFormat(palantirJavaFormatVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
