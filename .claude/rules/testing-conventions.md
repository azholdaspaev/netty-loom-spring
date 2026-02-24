# Testing Conventions

## Framework
- **JUnit 5** for test lifecycle
- **AssertJ** for assertions: `assertThat(...).isEqualTo(...)`
- **Mockito** with `@ExtendWith(MockitoExtension.class)`
- **ArchUnit** for module boundary verification
- **NOT:** Kotlin, MockK, Kotest, TestNG

## JVM Args
- `--enable-preview` (required for Java 24 features)
- `-Dio.netty.leakDetectionLevel=paranoid` (ByteBuf leak detection)

## Test Naming
- Method pattern: `shouldDoXWhenY` or `shouldDoX` (camelCase, **no underscores**)
- Class pattern: `{ClassName}Test` for unit, `{ClassName}IntegrationTest` for integration

## Test Structure
- **Every test** must use `// Given`, `// When`, `// Then` comments
- For combined assertion-and-action cases use `// When / Then`
```java
@Test
void shouldReturnOkWhenRequestIsValid() {
    // Given
    var request = createValidRequest();

    // When
    var result = handler.handle(request);

    // Then
    assertThat(result.status()).isEqualTo(HttpResponseStatus.OK);
}
```

## Running Tests
- Single class: `./gradlew :module:test --tests "*ClassName*"`
- Single module: `./gradlew :module:test`
- All modules: `./gradlew test`
- All checks (compile + test + spotless): `./gradlew check`

## ArchUnit Tests
- Location: `core/src/test/.../ArchitectureTest.java`
- Verify: `core` has no Spring imports
- Run with all other tests via `./gradlew check`

## Coverage
- Focus on public API and edge cases
- Every public method should have at least one test
- Thread-safety scenarios need dedicated tests
