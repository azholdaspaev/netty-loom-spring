# Project Conventions

## Testing Conventions

### Given-When-Then Pattern

All tests must follow the Given-When-Then (Arrange-Act-Assert) pattern with explicit comments:

```java
@Test
void shouldReturnHelloWorldForGetRequest() throws Exception {
    // Given
    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/test"))
            .GET()
            .build();

    // When
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    // Then
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("Hello World");
}
```

- **Given**: Set up test preconditions and inputs
- **When**: Execute the action being tested
- **Then**: Verify expected outcomes

### Test Organization

- Use `@BeforeAll`/`@AfterAll` for shared expensive resources (servers, connections)
- Use `@Nested` classes to group related tests with different setup requirements
- Use descriptive test method names that describe the expected behavior
