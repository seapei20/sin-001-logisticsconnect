package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

public class HubServiceApp {

    private static volatile List<Hub> hubs = List.of();
    private static final IngestionClient ingestion = new IngestionClient();

    public static void main(String[] args) {
        startBackfill();

        Javalin app = Javalin.create().start(7051);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/hubs", HubServiceApp::listHubs);
        app.get("/hubs/{hubId}", HubServiceApp::getHub);
        app.post("/refresh", HubServiceApp::refresh);
    }

    private static void startBackfill() {
        Thread worker = new Thread(() -> {
            while (hubs.isEmpty()) {
                try {
                    refresh();
                    return;
                } catch (Exception e) {
                    System.out.println("Ingestion not reachable yet, retrying in 3s: " + e.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private static void refresh(Context ctx) {
        try {
            refresh();
            ctx.json(Map.of("hubsLoaded", hubs.size()));
        } catch (Exception e) {
            ctx.status(503).json(Map.of("error", "Could not load hubs from ingestion-service: " + e.getMessage()));
        }
    }

    private static void refresh() throws Exception {
        List<Hub> fetched = ingestion.fetchHubs();
        hubs = fetched;
        System.out.println("Loaded " + hubs.size() + " hubs from ingestion-service");
    }

    private static void listHubs(Context ctx) {
        if (hubs.isEmpty()) {
            try {
                refresh();
            } catch (Exception e) {
                ctx.status(503).json(Map.of("error", "hub data not loaded: " + e.getMessage()));
                return;
            }
        }
        ctx.json(hubs);
    }

    private static void getHub(Context ctx) {
        if (hubs.isEmpty()) {
            try {
                refresh();
            } catch (Exception e) {
                ctx.status(503).json(Map.of("error", "hub data not loaded: " + e.getMessage()));
                return;
            }
        }

        String hubId = ctx.pathParam("hubId").toUpperCase();
        hubs.stream()
                .filter(h -> h.getHubId().equals(hubId))
                .findFirst()
                .ifPresentOrElse(
                        hub -> ctx.json(hub),
                        () -> ctx.status(404).json(Map.of("error", "Hub not found: " + hubId))
                );
    }
}