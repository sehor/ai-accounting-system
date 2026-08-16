package com.example.accounting.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OpenApiContractHttpTest {

    private static final URI OPENAPI_URI = URI.create(System.getProperty(
            "openapi.url", "http://127.0.0.1:18080/v1/openapi.json"));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesUnambiguousSchemasAndStringDecimals() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(OPENAPI_URI)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode document = objectMapper.readTree(response.body());
        JsonNode schemas = document.path("components").path("schemas");
        Set<String> schemaNames = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(schemas.fieldNames(), 0), false)
                .collect(Collectors.toSet());

        assertThat(schemas.has("VoucherLineRequest")).isTrue();
        assertThat(schemas.has("VoucherLineResponse")).isTrue();
        assertThat(schemas.has("VoucherDimensionRequest")).isTrue();
        assertThat(schemas.has("VoucherDimensionResponse")).isTrue();
        assertThat(schemas.has("AuditPage")).isTrue();
        assertThat(schemas.has("DimensionValuesBatchRequest")).isTrue();
        assertThat(schemas.has("DimensionValuesBatchResponse")).isTrue();
        assertThat(schemas.has("Line")).isFalse();
        assertThat(schemas.has("Create")).isFalse();
        assertThat(schemas.has("Page")).isFalse();
        assertThat(schemas.has("Statement")).isFalse();
        assertThat(schemas.has("Dimension")).isFalse();

        JsonNode voucherLine = schemas.path("VoucherLineResponse");
        assertThat(StreamSupport.stream(voucherLine.path("required").spliterator(), false)
                .map(JsonNode::asText).toList()).contains("id", "lineNo", "baseAmount");
        assertThat(voucherLine.path("properties").path("baseAmount").path("type").asText())
                .isEqualTo("string");

        schemas.forEach(schema -> schema.path("properties").forEach(property ->
                assertThat(property.path("type").asText()).isNotEqualTo("number")));
        assertThat(document.path("paths").has("/v1/ledgers/{ledgerId}/dimension-values:batch")).isTrue();
        assertThat(schemaNames).doesNotContain("Line", "Create", "Page", "Statement", "Dimension");
    }
}
