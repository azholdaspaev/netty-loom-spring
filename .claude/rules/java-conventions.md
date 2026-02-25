# Java Conventions

## Language Version
- Java 24 with `--enable-preview` enabled
- Target toolchain: Java 24

## Formatting
- palantir-java-format PALANTIR style (4-space indent, +8 continuation, 120-char line width)
- Enforced by Spotless Gradle plugin
- Run `./gradlew spotlessApply` to auto-fix
- Run `./gradlew spotlessCheck` to verify

## Language Features
- **Records** for DTOs and value objects
- **Sealed interfaces** for type hierarchies
- **Pattern matching** (`instanceof`, `switch`) where appropriate
- **Virtual threads** for blocking operations

## Annotations
- `@Override` on all overridden methods

## Concurrency
- `ReentrantLock` over `synchronized` (avoids virtual thread pinning)
- `AtomicReference` / `volatile` for cross-thread shared state
- Document thread-safety contracts on public classes

## Base Package
- `io.github.azholdaspaev.nettyloom`
- Sub-packages by module: `.core`, `.mvc`, `.starter`
