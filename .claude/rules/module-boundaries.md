# Module Boundaries

## Module Structure

### `core` (netty-loom-spring-core)
- **Dependencies:** Netty only. NO Spring, NO Servlet API.
- **Purpose:** Netty server, channel handlers, virtual thread integration
- **Enforced by:** ArchUnit tests in `core/src/test/.../ArchitectureTest.java`
- **Violation example:** Importing `org.springframework.*` in core → ArchUnit test fails

### `mvc` (netty-loom-spring-mvc)
- **Dependencies:** `core` + Spring Web + Spring WebMVC
- **Purpose:** Bridge between Netty channels and Spring's `DispatcherServlet`
- **Key classes:** Request/response adapters, servlet implementations

### `starter` (netty-loom-spring-boot-starter)
- **Dependencies:** `core` + `mvc` + Spring Boot Autoconfigure
- **Purpose:** Auto-configuration, property binding (`netty.loom.*`)
- **Excludes:** Tomcat (users get Netty instead)
- **Key annotations:** `@ConditionalOnClass`, `@ConditionalOnMissingBean`

### `example-netty`
- **Plugin:** `application` (not `java-library`)
- **Purpose:** Pure Netty usage example without Spring

### `example-tomcat`
- **Plugin:** `application` (not `java-library`)
- **Purpose:** Comparison example using Tomcat

## Rules
1. Lower modules MUST NOT depend on higher modules (`core` cannot import from `mvc`)
2. Example modules depend on everything but nothing depends on them
3. New classes go in the lowest possible module
4. If a class needs Spring → it goes in `mvc` or `starter`, never `core`
