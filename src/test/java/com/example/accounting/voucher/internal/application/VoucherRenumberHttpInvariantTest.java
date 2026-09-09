package com.example.accounting.voucher.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in verification against an already running local backend; no Spring test context. */
@EnabledIfEnvironmentVariable(named = "VERIFY_RUNNING_BACKEND", matches = "true")
class VoucherRenumberHttpInvariantTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base = System.getenv().getOrDefault("VERIFY_BACKEND_URL", "http://127.0.0.1:8080");

    @Test
    void renumberingDoesNotChangeAnyBalanceProjectionOrEnqueueRebuilds() throws Exception {
        JsonNode ledger = api("POST", "/v1/ledgers", """
                {"name":"renumber-projection-%s","accountingStandardCode":"SME","accountingStandardVersion":"v1",
                 "baseCurrency":"CNY","startDate":"2026-01-01","approvalEnabled":false}
                """.formatted(UUID.randomUUID()));
        UUID ledgerId = UUID.fromString(ledger.path("id").asText());
        String root = "/v1/ledgers/" + ledgerId;
        try (Connection connection = DriverManager.getConnection(System.getenv("VERIFY_DB_URL"),
                System.getenv("VERIFY_DB_USERNAME"), System.getenv("VERIFY_DB_PASSWORD"))) {
            JsonNode periods = api("GET", root + "/periods", null);
            String periodId = null;
            for (JsonNode period : periods) if ("2026-01".equals(period.path("periodCode").asText())) periodId = period.path("id").asText();
            String debit = null, credit = null;
            for (JsonNode account : api("GET", root + "/accounts", null)) {
                if ("1001".equals(account.path("code").asText())) debit = account.path("id").asText();
                if ("3001".equals(account.path("code").asText())) credit = account.path("id").asText();
            }
            assertThat(periodId).isNotNull();
            assertThat(debit).isNotNull();
            assertThat(credit).isNotNull();
            api("POST", root + "/vouchers", """
                    {"periodId":"%s","voucherDate":"2026-01-15","voucherType":"记","voucherNumber":"10",
                     "summary":"projection invariant","lines":[
                     {"accountId":"%s","side":"DEBIT","currency":"CNY","originalAmount":"10","exchangeRate":"1"},
                     {"accountId":"%s","side":"CREDIT","currency":"CNY","originalAmount":"10","exchangeRate":"1"}]}
                    """.formatted(periodId, debit, credit));
            boolean ready = false;
            for (int attempt = 0; attempt < 40; attempt++) {
                try (var query = connection.prepareStatement("""
                        select not exists (select 1 from balance_projection_state where ledger_id = ?
                          and (status <> 'READY' or last_applied_event_id is distinct from last_enqueued_event_id))
                        and not exists (select 1 from balance_rebuild_job where ledger_id = ? and status in ('QUEUED','RUNNING'))
                        """)) {
                    query.setObject(1, ledgerId); query.setObject(2, ledgerId);
                    try (var result = query.executeQuery()) { result.next(); ready = result.getBoolean(1); }
                }
                if (ready) break;
                Thread.sleep(250);
            }
            assertThat(ready).as("fixture projection caught up before measuring renumbering").isTrue();
            List<String> before = snapshot(connection, ledgerId);
            String parameters = "{\"periodId\":\"" + periodId + "\",\"mode\":\"DATE_ORDER\",\"startNumber\":\"1\"}";
            JsonNode preview = api("POST", root + "/vouchers/renumber:preview", parameters);
            String execute = parameters.substring(0, parameters.length() - 1)
                    + ",\"previewFingerprint\":\"" + preview.path("previewFingerprint").asText() + "\"}";
            assertThat(api("POST", root + "/vouchers/renumber:execute", execute).path("changedCount").asInt()).isEqualTo(1);
            assertThat(snapshot(connection, ledgerId)).as("events, event positions, rebuild jobs and every balance row stay identical").isEqualTo(before);
        } finally {
            api("DELETE", "/v1/admin/ledgers/" + ledgerId, null);
        }
    }

    private List<String> snapshot(Connection connection, UUID ledgerId) throws Exception {
        List<String> snapshots = new ArrayList<>();
        for (String table : List.of("balance_projection_event", "balance_projection_state", "balance_rebuild_job",
                "account_period_balance", "dimension_period_balance")) {
            try (var query = connection.prepareStatement("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]'::jsonb)::text from "
                    + table + " t where ledger_id = ?")) {
                query.setObject(1, ledgerId);
                try (var result = query.executeQuery()) { result.next(); snapshots.add(result.getString(1)); }
            }
        }
        return snapshots;
    }

    private JsonNode api(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("X-User-Name", "admin").header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString());
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isBetween(200, 299);
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }
}
