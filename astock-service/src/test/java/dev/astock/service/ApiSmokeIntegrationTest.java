package dev.astock.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:astock_api;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ApiSmokeIntegrationTest {
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void exposesPublicHealthButProtectsAndSerializesBusinessApi() throws Exception {
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");

        HttpResponse<String> unauthorized = http.send(HttpRequest.newBuilder(uri("/api/v1/market/quotes"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unauthorized.statusCode()).isEqualTo(401);

        HttpResponse<String> quotes = http.send(HttpRequest.newBuilder(uri("/api/v1/market/quotes"))
                        .header("X-AStock-Key", "integration-test-key").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(quotes.statusCode()).isEqualTo(200);
        assertThat(quotes.body()).contains(
                "SH.600000", "HK.00700", "US.AAPL", "sourceTimestamp", "bid1Price"
        );

        HttpResponse<String> status = http.send(HttpRequest.newBuilder(uri("/api/v1/admin/status"))
                        .header("X-AStock-Key", "integration-test-key").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("markets", "CN", "HK", "US", "executionOpen");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
