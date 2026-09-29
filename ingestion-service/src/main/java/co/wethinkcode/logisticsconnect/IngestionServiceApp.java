package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class IngestionServiceApp {

    /** The legacy export this service exists to clean. */
    static final Path CSV_PATH = Path.of("src/main/resources/hubs-global.csv");

    /** Package-private so tests can assert against the same instance the routes serve. */
    static volatile List<Hub> hubs = List.of();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7050);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/hubs", IngestionServiceApp::listHubs);
        app.get("/hubs/{hubId}", IngestionServiceApp::getHub);

        reload();
    }

    /**
     * Cleans the CSV and caches the result.
     *
     * <p>Cached at startup rather than per request because the source is a file on disk that
     * does not change while the service runs — re-parsing per request would mean every caller
     * paying for a full clean to get data that cannot have changed.
     */
    static void reload() {
        try {
            hubs = CsvCleaner.clean(CSV_PATH);
            System.out.println("Ingested " + hubs.size() + " hubs from " + CSV_PATH);
            hubs.forEach(System.out::println);
        } catch (IOException e) {
            // Fail loudly but do not take the service down: /health should still answer so the
            // failure is visible as missing data, not as a dead port nobody can diagnose.
            System.err.println("Failed to read " + CSV_PATH + ": " + e.getMessage());
        }
    }

    private static void listHubs(Context ctx) {
        ctx.json(hubs);
    }

    private static void getHub(Context ctx) {
        String hubId = ctx.pathParam("hubId").toUpperCase(Locale.ROOT);
        hubs.stream()
                .filter(h -> h.hubId().equals(hubId))
                .findFirst()
                .ifPresentOrElse(
                        ctx::json,
                        () -> ctx.status(404).json(java.util.Map.of("error", "Hub not found: " + hubId)));
    }
}
