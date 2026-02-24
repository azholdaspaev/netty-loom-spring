# netty-loom-spring

A Java 24 library connecting Netty with Spring Web via virtual threads.

## Project Overview

This library provides seamless integration between Netty's high-performance networking and Spring's web framework, leveraging Java 24's virtual threads (Project Loom) for efficient handling of blocking operations.

## Build Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run all checks (compile + test + spotless)
./gradlew check

# Clean build
./gradlew clean build

# Format code
./gradlew spotlessApply
```

## Project Conventions

### Java 24
- Use virtual threads for blocking operations
- Use records for DTOs
- Use sealed interfaces for type hierarchies
- Apply @Nullable/@NonNull annotations

### Netty
- Never block EventLoop threads
- Always release ByteBuf after use
- Use virtual thread executor for blocking work

### Testing
- All tests must use Given/When/Then structure with `// Given`, `// When`, `// Then` comments
- For combined assertion-and-action cases use `// When / Then`
- Test method names use camelCase with no underscores: `shouldDoXWhenY`

### Spring
- Use @ConditionalOnClass, @ConditionalOnMissingBean
- Configuration properties prefix: `netty.loom.*`
- Support standard Spring MVC annotations
