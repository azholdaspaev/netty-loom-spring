package io.github.azholdaspaev.nettyloom.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Base class for integration tests that provides common configuration and utilities.
 * All integration tests should extend this class to get access to:
 * <ul>
 *   <li>Automatic server startup on a random port</li>
 *   <li>TestRestTemplate for making HTTP requests</li>
 *   <li>Utility methods for common HTTP operations</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    /**
     * Makes a GET request and returns the response.
     *
     * @param path the request path (e.g., "/api/users")
     * @param responseType the expected response type
     * @param <T> the response body type
     * @return the response entity
     */
    protected <T> ResponseEntity<T> get(String path, Class<T> responseType) {
        return restTemplate.getForEntity(path, responseType);
    }

    /**
     * Makes a POST request with a JSON body and returns the response.
     *
     * @param path the request path
     * @param body the request body
     * @param responseType the expected response type
     * @param <T> the response body type
     * @return the response entity
     */
    protected <T> ResponseEntity<T> post(String path, Object body, Class<T> responseType) {
        return restTemplate.postForEntity(path, body, responseType);
    }

    /**
     * Makes a GET request with custom headers.
     *
     * @param path the request path
     * @param headers the HTTP headers
     * @param responseType the expected response type
     * @param <T> the response body type
     * @return the response entity
     */
    protected <T> ResponseEntity<T> getWithHeaders(String path, HttpHeaders headers, Class<T> responseType) {
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(path, HttpMethod.GET, entity, responseType);
    }

    /**
     * Makes a POST request with custom headers and body.
     *
     * @param path the request path
     * @param body the request body
     * @param headers the HTTP headers
     * @param responseType the expected response type
     * @param <T> the response body type
     * @return the response entity
     */
    protected <T> ResponseEntity<T> postWithHeaders(String path, Object body, HttpHeaders headers, Class<T> responseType) {
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(path, HttpMethod.POST, entity, responseType);
    }

    /**
     * Creates HttpHeaders with Content-Type set to application/json.
     *
     * @return headers with JSON content type
     */
    protected HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Creates HttpHeaders with Accept set to application/json.
     *
     * @return headers with JSON accept type
     */
    protected HttpHeaders acceptJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }
}
