package com.example.accounting.documents.internal.integration;

import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.internal.port.DocumentExtractor;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpDocumentExtractor implements DocumentExtractor {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final String endpoint;
    private final String apiKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public HttpDocumentExtractor(@Value("${app.documents.extractor-url:}") String endpoint,
                                 @Value("${app.documents.extractor-api-key:}") String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public Result extract(DocumentResponses.Document document, byte[] content) {
        URI uri = endpoint();
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("fileName", document.fileName());
            payload.put("contentType", document.contentType());
            payload.put("contentBase64", Base64.getEncoder().encodeToString(content));
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            if (apiKey != null && !apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw problem(502, "DOCUMENT_EXTRACTOR_FAILED", "Document extractor failed",
                        response.statusCode() == 429 || response.statusCode() >= 500);
            }
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw problem(502, "DOCUMENT_EXTRACTOR_RESPONSE_TOO_LARGE",
                        "Document extractor response is too large", false);
            }
            JsonNode result = objectMapper.readTree(response.body());
            validate(result);
            JsonNode references = result.path("sourceReferences");
            return new Result(header(response, "X-Extractor-Provider", "http"),
                    header(response, "X-Extractor-Version", "v1"),
                    objectMapper.writeValueAsString(result),
                    references.isMissingNode() ? "{}" : objectMapper.writeValueAsString(references));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw problem(502, "DOCUMENT_EXTRACTOR_INTERRUPTED", "Document extraction was interrupted", true);
        } catch (IOException exception) {
            throw problem(502, "DOCUMENT_EXTRACTOR_UNAVAILABLE", "Document extractor is unavailable", true);
        }
    }

    private URI endpoint() {
        if (endpoint == null || endpoint.isBlank()) {
            throw problem(503, "DOCUMENT_EXTRACTOR_NOT_CONFIGURED", "Document extractor is not configured", false);
        }
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException exception) {
            throw problem(503, "DOCUMENT_EXTRACTOR_NOT_CONFIGURED", "Document extractor is not configured", false);
        }
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !loopbackHttp) {
            throw problem(503, "DOCUMENT_EXTRACTOR_URL_INSECURE",
                    "Document extractor must use HTTPS", false);
        }
        return uri;
    }

    private void validate(JsonNode result) {
        if (!result.isObject()) {
            throw problem(502, "DOCUMENT_EXTRACTOR_RESULT_INVALID", "Document extractor result is invalid", false);
        }
        try {
            if (new BigDecimal(result.path("totalAmount").asText()).signum() <= 0
                    || !result.path("currency").asText().matches("[A-Z]{3}")) {
                throw new IllegalArgumentException();
            }
            JsonNode rate = result.path("exchangeRate");
            if (rate.isMissingNode() || new BigDecimal(rate.asText()).signum() <= 0) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw problem(502, "DOCUMENT_EXTRACTOR_RESULT_INVALID", "Document extractor result is invalid", false);
        }
    }

    private String header(HttpResponse<?> response, String name, String fallback) {
        String value = response.headers().firstValue(name).orElse(fallback);
        return value.length() <= 64 ? value : fallback;
    }

    private ApiProblemException problem(int status, String code, String detail, boolean retryable) {
        return new ApiProblemException(status, code, "Document extraction failed", detail, retryable);
    }
}
