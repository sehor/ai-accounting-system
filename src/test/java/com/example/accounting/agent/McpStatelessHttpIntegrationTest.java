package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpStatelessHttpIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void initializeListAndCallNeedNoSessionAndGetIsNotHeldOpen() throws Exception {
        long initializeStarted = System.nanoTime();
        HttpResponse<String> initialize = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26","capabilities":{},
                  "clientInfo":{"name":"stateless-test","version":"1"}}}
                """);
        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - initializeStarted)).isLessThan(Duration.ofSeconds(1));
        assertThat(initialize.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(initialize.body()).contains("protocolVersion");
        assertThat(initialize.body()).doesNotContain("\"resources\"", "\"prompts\"", "\"completions\"");

        HttpResponse<String> tools = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        assertThat(tools.statusCode()).isEqualTo(200);
        assertThat(tools.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(tools.body()).contains("get_operator_context", "get_ledger_context");

        HttpResponse<String> call = post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"get_current_user","arguments":{}}}
                """);
        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(call.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(call.body()).contains("super-agent");

        HttpResponse<String> get = http.send(HttpRequest.newBuilder(endpoint())
                        .header("Accept", "text/event-stream")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(405);
    }

    @Test
    void warmReadCallsStayBelowTheNativeMcpLatencyBudget() throws Exception {
        String request = """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"get_current_user","arguments":{}}}
                """;
        for (int index = 0; index < 5; index++) {
            assertThat(post(request).statusCode()).isEqualTo(200);
        }
        List<Duration> durations = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            long started = System.nanoTime();
            assertThat(post(request).statusCode()).isEqualTo(200);
            durations.add(Duration.ofNanos(System.nanoTime() - started));
        }
        durations.sort(Comparator.naturalOrder());
        Duration p95 = durations.get((int) Math.ceil(durations.size() * 0.95) - 1);

        assertThat(p95).isLessThan(Duration.ofMillis(100));
    }

    @Test
    void updateVoucherSchemaAllowsNullableVoucherFieldsToBeOmitted() throws Exception {
        HttpResponse<String> tools = post("""
                {"jsonrpc":"2.0","id":5,"method":"tools/list","params":{}}
                """);

        JsonNode updateVoucher = objectMapper.readTree(tools.body())
                .path("result").path("tools").valueStream()
                .filter(tool -> "update_voucher".equals(tool.path("name").asText()))
                .findFirst().orElseThrow();
        JsonNode requestSchema = updateVoucher.path("inputSchema").path("properties").path("request");
        JsonNode lineSchema = requestSchema.path("properties").path("lines").path("items");

        assertThat(requestSchema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("expectedVersion", "periodId", "voucherDate", "voucherType",
                        "voucherNumber", "lines");
        assertThat(lineSchema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("accountId", "side", "currency", "originalAmount", "exchangeRate");
    }

    @Test
    void accountImportExposesOnlyTheAtomicBatchDecisionTool() throws Exception {
        HttpResponse<String> tools = post("""
                {"jsonrpc":"2.0","id":6,"method":"tools/list","params":{}}
                """);

        List<String> names = objectMapper.readTree(tools.body()).path("result").path("tools").valueStream()
                .map(tool -> tool.path("name").asText())
                .toList();

        assertThat(names).contains("decide_account_import_rows")
                .doesNotContain("decide_account_import_row");
    }

    @Test
    void accountSearchExposesExactAndFuzzyModes() throws Exception {
        HttpResponse<String> tools = post("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}
                """);

        JsonNode searchAccounts = objectMapper.readTree(tools.body())
                .path("result").path("tools").valueStream()
                .filter(tool -> "search_accounts".equals(tool.path("name").asText()))
                .findFirst().orElseThrow();
        JsonNode schema = searchAccounts.path("inputSchema");

        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("ledgerId", "query");
        assertThat(schema.path("properties").path("matchMode").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("EXACT", "FUZZY");
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(endpoint())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + port + "/mcp");
    }
}
