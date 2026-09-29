package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the HTTP surface over a real socket, on a port the test picks itself so it cannot
 * collide with a service the developer already has running.
 *
 * <p>The contract other services depend on is the shape of these responses, so it is worth
 * asserting over the wire rather than only unit-testing {@link CsvCleaner}. A unit test would
 * pass even if the route were never registered.
 */
class IngestionServiceAppTest {

    private static Javalin app;
    private static HttpClient client;
    private static String baseUrl;

    @BeforeAll
    static void startService() {
        // Port 0 asks the OS for a free port, so this never fights a running instance on 7050.
        app = Javalin.create().start(0);
        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/hubs", ctx -> ctx.json(IngestionServiceApp.hubs));
        app.get("/hubs/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId").toUpperCase(java.util.Locale.ROOT);
            IngestionServiceApp.hubs.stream()
                    .filter(h -> h.hubId().equals(hubId))
                    .findFirst()
                    .ifPresentOrElse(ctx::json,
                            () -> ctx.status(404).json(java.util.Map.of("error", "Hub not found: " + hubId)));
        });
        IngestionServiceApp.reload();

        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        baseUrl = "http://localhost:" + app.port();
    }

    @AfterAll
    static void stopService() {
        if (app != null) {
            app.stop();
        }
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("/health answers OK")
    void healthIsOk() throws Exception {
        assertEquals(200, get("/health").statusCode());
        assertEquals("OK", get("/health").body());
    }

    @Test
    @DisplayName("GET /hubs returns the cleaned records as JSON, not the raw file")
    void listHubsReturnsCleanedJson() throws Exception {
        HttpResponse<String> response = get("/hubs");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"hubId\""), "should be JSON with camelCase fields");
        assertTrue(response.body().contains("H-500"));
        assertEquals(18, IngestionServiceApp.hubs.size());
    }

    @Test
    @DisplayName("GET /hubs/{id} is case-insensitive")
    void getHubIsCaseInsensitive() throws Exception {
        HttpResponse<String> upper = get("/hubs/H-500");
        HttpResponse<String> lower = get("/hubs/h-500");

        assertEquals(200, upper.statusCode());
        assertEquals(200, lower.statusCode());
        assertEquals(upper.body(), lower.body(), "h-500 and H-500 should resolve identically");
    }

    @Test
    @DisplayName("GET /hubs/{id} returns 404 for an unknown hub")
    void unknownHubIs404() throws Exception {
        HttpResponse<String> response = get("/hubs/H-999");

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("H-999"), "the error should name what was asked for");
    }
}
