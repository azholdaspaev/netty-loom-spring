# Coding Conventions

## Package Structure

```
io.github.azholdaspaev.nettyloom.<module>.<feature>
```

| Module | Packages |
|--------|----------|
| core | `handler`, `http`, `pipeline`, `server` |
| mvc | `handler`, `servlet`, `exception` |
| starter | `autoconfigure`, `autoconfigure.server` |

## Naming

| Pattern | Usage | Example |
|---------|-------|---------|
| `Default*` | Concrete implementation of interface | `DefaultNettyHttpRequest` |
| `*Handler` | Netty channel handler or request handler | `RequestDispatcher` |
| `*Configurer` | Pipeline/config builder | `HttpServerNettyPipelineConfigurer` |
| `*Converter` | Format conversion | `DefaultNettyHttpRequestConverter` |
| `*Server` | Server implementation | `NettyServer`, `NettyWebServer` |
| `*Initializer` | Netty channel init | `NettyServerInitializer` |
| `*State` | State enum | `NettyServerState` |

## Class Design

- **Visibility**: Package-private by default. `public` only for API surfaces.
- **Records**: Use for immutable data/config (`NettyServerConfig`).
- **Builders**: Nested static class, factory method `builder()`, fluent setters returning `this`, terminal `build()`.
- **Functional interfaces**: Annotate with `@FunctionalInterface` (`RequestHandler`, `ExceptionHandler`).
- **Enums**: For finite state (`NettyServerState`) and fixed sets (`HttpMethod`).

## Netty Patterns

- `@ChannelHandler.Sharable` on handlers shared across channels (e.g., `RequestDispatcher`).
- Extend `ChannelInboundHandlerAdapter` for inbound handlers; override `channelRead()` and `exceptionCaught()`.
- Extend `ChannelInitializer<SocketChannel>` for pipeline setup.
- Pipeline order: HttpServerCodec → HttpObjectAggregator → IdleStateHandler → custom decoders → custom encoders → dispatcher.
- Check `ctx.channel().isActive()` before writing.
- Use `ChannelFutureListener.CLOSE` for non-keep-alive connections.
- Virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` for request handling — never block Netty's event loop.

## Error Handling

- `ExceptionHandler` functional interface returns `NettyHttpResponse` for errors.
- Try-catch in `channelRead()`; if exception handler itself fails, close channel.
- Log with request context at each stage.
- `NotImplementedException` for unimplemented servlet features.

## Testing

### Structure

```java
@ExtendWith(MockitoExtension.class)
class FooTest {                          // package-private, no public

    @Mock
    private Dependency dep;

    private Foo testSubject;

    @BeforeEach
    void setUp() { /* ... */ }

    @Test
    void shouldDoSomethingWhenCondition() {
        // Given
        var input = givenInput();

        // When
        var result = testSubject.doSomething(input);

        // Then
        assertThat(result).isEqualTo(expected);
        verify(dep).wasCalled(input);
    }

    // ── Helpers ───────────────────────────────────────────

    private Input givenInput() { /* ... */ }
}
```

### Rules

| Rule | Detail |
|------|--------|
| Naming | `should<Behavior>[When<Condition>]` |
| Sections | `// Given`, `// When`, `// Then` comments when tests have distinct phases; trivial tests may omit them |
| Assertions | AssertJ only (`assertThat`) |
| Mocking | Mockito (`@Mock`, `when`, `verify`, `argThat`) |
| Visibility | Package-private classes and methods (exception: `@SpringBootTest` classes may need `public`) |
| Helpers | `given*()` for setup, descriptive names for assertion helpers |
| Section headers | `// ── Section Name ────────────────` for grouping related tests |
| Virtual threads | `SynchronousExecutorService` (extends `AbstractExecutorService`, runs inline) for deterministic tests |
| Resources | `@AfterEach` for cleanup (ByteBuf release, server shutdown) |

### Testing Pyramid

1. **Unit tests** (majority) — isolated classes with mocks, Given/When/Then comments, package-private.
2. **Integration tests** (fewer) — `@SpringBootTest` with `RANDOM_PORT`, `TestRestTemplate`. May be `public`. Use compact arrange-act-assert style; section headers (`// ── Section ──`) to group by endpoint.
3. **Architecture tests** — ArchUnit rules enforcing module boundaries (core has no Spring/Servlet deps).

### Parameterized Tests

```java
@ParameterizedTest
@ValueSource(strings = {"a", "b", "c"})
void shouldHandleVariants(String input) { /* ... */ }
```

Use `@ValueSource`, `@NullSource`, `@EnumSource` where applicable.

### ArchUnit Pattern

```java
class ArchitectureTest {
    private static JavaClasses coreClasses;

    @BeforeAll
    static void importClasses() {
        coreClasses = new ClassFileImporter()
                .withImportOption(DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.azholdaspaev.nettyloom.core");
    }

    @Test
    void coreShouldNotDependOnSpring() {
        noClasses().that()
                .resideInAPackage("io.github.azholdaspaev.nettyloom.core..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .check(coreClasses);
    }
}
```

## CI

- GitHub Actions: push/PR to main
- Matrix: ubuntu-latest + macos-latest, Java 24 (temurin)
- Command: `./gradlew build`
- Test reports uploaded on failure
