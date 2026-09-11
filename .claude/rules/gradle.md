---
paths:
  - "*.gradle.kts"
  - "**/*.gradle.kts"
  - "gradle/**"
  - "gradle.properties"
---

# Gradle build rules

- **Gradle 9.4.1**, Kotlin DSL, Java 25 toolchain. No `--enable-preview`: the library targets only stable JDK features, so consumers need no JVM flags
- **Spring Boot BOM 4.0.5** — Spring/Jakarta deps need no explicit version. `mvc` and `starter` get it as a Gradle platform on the `springBom` dependencyScope declared in root; the two examples still use the `io.spring.dependency-management` plugin. The plugin cannot be used in a published module: it writes no versions into Gradle module metadata, so consumers cannot resolve (#147). It also *overrides* transitive versions where a platform only raises them
- **The BOM must not reach a published variant.** Keep it on the `springBom` dependencyScope, never `api(platform(...))`, and keep `versionMapping { allVariants { fromResolutionResult() } }` — neither works without the other. Rationale and the measured consequence live at the `springBom` declaration in `build.gradle.kts` (#30)
- **Version catalog** at `gradle/libs.versions.toml` for Netty 4.2.12.Final, JUnit 6.0.3, etc. Spring BOM-managed deps (spring-web, jackson-databind, ...) take no version there
- Native transport deps use classifier variants: `variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") }`
- `the<DependencyManagementExtension>()` doesn't work inside `subprojects {}` — use `pluginManager.withPlugin("io.spring.dependency-management") { configure<...> {} }` instead
- `libs` version catalog accessor is not available inside `subprojects {}` blocks — extract needed values to `val` at root level first
- Every test task uses `useJUnitPlatform()` and runs with `--enable-native-access=ALL-UNNAMED`, which the native epoll and kqueue transports need
