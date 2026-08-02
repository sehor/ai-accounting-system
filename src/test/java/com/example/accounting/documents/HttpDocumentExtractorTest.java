package com.example.accounting.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.documents.internal.integration.HttpDocumentExtractor;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpDocumentExtractorTest {

    @Test
    void sendsDocumentToConfiguredExtractorAndValidatesStructuredResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/extract", exchange -> {
            byte[] response = """
                    {"totalAmount":"12.34","currency":"CNY","exchangeRate":"1","sourceReferences":[]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Extractor-Provider", "local-test");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var extractor = new HttpDocumentExtractor(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/extract", "");
            var document = new DocumentResponses.Document(UUID.randomUUID(), UUID.randomUUID(), "key",
                    "invoice.pdf", "application/pdf", 3, "hash", "UPLOADED", false, OffsetDateTime.now());

            var result = extractor.extract(document, new byte[]{1, 2, 3});

            assertThat(result.provider()).isEqualTo("local-test");
            assertThat(result.structuredResult()).contains("\"totalAmount\":\"12.34\"");
        } finally {
            server.stop(0);
        }
    }
}
