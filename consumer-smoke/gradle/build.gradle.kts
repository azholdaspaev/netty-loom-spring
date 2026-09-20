plugins {
    java
    id("org.springframework.boot")
}

val smokeRepository: String by project
val smokeVersion: String by project

repositories {
    mavenCentral()
    maven(smokeRepository)
}

sourceSets.main {
    java.srcDir("../src/main/java")
}

val auxiliaryArtifacts by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation("io.github.azholdaspaev:netty-loom-spring-boot-starter:$smokeVersion")

    listOf("core", "mvc", "boot-starter").forEach { module ->
        listOf("sources", "javadoc").forEach { classifier ->
            auxiliaryArtifacts("io.github.azholdaspaev:netty-loom-spring-$module:$smokeVersion:$classifier")
        }
    }
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

val runtimeClasspath = configurations.runtimeClasspath.map { it.files }
tasks.register("resolveRuntimeClasspath") {
    doLast { runtimeClasspath.get() }
}

val auxiliaryFiles = auxiliaryArtifacts.incoming.files
tasks.register("resolveAuxiliaryArtifacts") {
    doLast { auxiliaryFiles.files }
}
