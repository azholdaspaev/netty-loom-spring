package io.github.azholdaspaev.nettyloom.mvc.request;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for parsing HTTP request parameters from query strings
 * and application/x-www-form-urlencoded request bodies.
 */
public final class ParameterParser {

    private static final String CONTENT_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded";

    private ParameterParser() {
    }

    /**
     * Parses a query string into a map of parameter names to values.
     * Handles multiple values for the same parameter name.
     *
     * @param queryString the query string to parse (without the leading '?')
     * @param charset the character encoding to use for URL decoding
     * @return a map of parameter names to lists of values
     */
    public static Map<String, List<String>> parseQueryString(String queryString, Charset charset) {
        if (queryString == null || queryString.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<String>> parameters = new LinkedHashMap<>();
        String[] pairs = queryString.split("&");

        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }

            int eqIdx = pair.indexOf('=');
            String name;
            String value;

            if (eqIdx > 0) {
                name = urlDecode(pair.substring(0, eqIdx), charset);
                value = urlDecode(pair.substring(eqIdx + 1), charset);
            } else if (eqIdx == 0) {
                // "=value" - skip invalid parameter
                continue;
            } else {
                // "name" without value
                name = urlDecode(pair, charset);
                value = "";
            }

            parameters.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }

        return parameters;
    }

    /**
     * Parses a query string using UTF-8 encoding.
     *
     * @param queryString the query string to parse
     * @return a map of parameter names to lists of values
     */
    public static Map<String, List<String>> parseQueryString(String queryString) {
        return parseQueryString(queryString, StandardCharsets.UTF_8);
    }

    /**
     * Parses form-encoded request body content.
     *
     * @param body the request body content
     * @param charset the character encoding to use
     * @return a map of parameter names to lists of values
     */
    public static Map<String, List<String>> parseFormBody(String body, Charset charset) {
        return parseQueryString(body, charset);
    }

    /**
     * Parses form-encoded request body content using UTF-8 encoding.
     *
     * @param body the request body content
     * @return a map of parameter names to lists of values
     */
    public static Map<String, List<String>> parseFormBody(String body) {
        return parseFormBody(body, StandardCharsets.UTF_8);
    }

    /**
     * Checks if the given content type indicates form-urlencoded data.
     *
     * @param contentType the content type header value
     * @return true if the content type is form-urlencoded
     */
    public static boolean isFormUrlEncoded(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lowerContentType = contentType.toLowerCase();
        return lowerContentType.startsWith(CONTENT_TYPE_FORM_URLENCODED);
    }

    /**
     * Merges two parameter maps into a single map.
     * Values from both maps are preserved.
     *
     * @param first the first parameter map
     * @param second the second parameter map
     * @return a merged map containing all parameters
     */
    public static Map<String, List<String>> merge(
            Map<String, List<String>> first,
            Map<String, List<String>> second) {

        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }

        Map<String, List<String>> merged = new LinkedHashMap<>(first);
        second.forEach((key, values) ->
                merged.merge(key, values, (existing, newValues) -> {
                    List<String> combined = new ArrayList<>(existing);
                    combined.addAll(newValues);
                    return combined;
                })
        );

        return merged;
    }

    /**
     * Converts a parameter map to an array-based map as required by
     * {@link jakarta.servlet.ServletRequest#getParameterMap()}.
     *
     * @param parameters the parameter map with lists
     * @return a map with String array values
     */
    public static Map<String, String[]> toArrayMap(Map<String, List<String>> parameters) {
        Map<String, String[]> arrayMap = new LinkedHashMap<>();
        parameters.forEach((key, values) ->
                arrayMap.put(key, values.toArray(new String[0]))
        );
        return Collections.unmodifiableMap(arrayMap);
    }

    private static String urlDecode(String value, Charset charset) {
        try {
            return URLDecoder.decode(value, charset);
        } catch (IllegalArgumentException e) {
            // If decoding fails, return the original value
            return value;
        }
    }
}
