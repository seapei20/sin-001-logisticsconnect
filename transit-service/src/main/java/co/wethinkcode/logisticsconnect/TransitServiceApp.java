package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TransitServiceApp {

    private static final HubClient hubClient = new HubClient();
    private static final DelayStageClient stageClient = new DelayStageClient();

    private static final Map<Integer, Integer> DELAY_MINUTES_BY_STAGE = Map.ofEntries(
            Map.entry(0, 0),
            Map.entry(1, 30),
            Map.entry(2, 90),
            Map.entry(3, 180),
            Map.entry(4, 300),
            Map.entry(5, 480),
            Map.entry(6, 720),
            Map.entry(7, 1080),
            Map.entry(8, 1440));

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7053);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/eta/{hubId}", TransitServiceApp::etaForHub);
    }

    private static void etaForHub(Context ctx) {
        String hubId = ctx.pathParam("hubId");

        Hub hub;
        try {
            hub = hubClient.fetchHub(hubId).orElse(null);
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", "hub-service unavailable: " + e.getMessage()));
            return;
        }
        if (hub == null) {
            ctx.status(404).json(Map.of("error", "Hub not found: " + hubId));
            return;
        }

        int stage;
        try {
            stage = stageClient.fetchStage(hubId).stage();
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", "delay-stage-service unavailable: " + e.getMessage()));
            return;
        }
        // MQ TODO (stage 3): delay stage will arrive via MqConfig.TOPIC instead of the direct REST call above

        int baseMinutes = baseTransitMinutes(hubId);
        int delayMinutes = DELAY_MINUTES_BY_STAGE.getOrDefault(stage, 0);
        int etaMinutes = baseMinutes + delayMinutes;
        Instant eta = Instant.now().plusSeconds(etaMinutes * 60L);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hubId", hub.getHubId());
        response.put("province", hub.getProvince());
        response.put("sortingCenter", hub.getSortingCenter());
        response.put("delayStage", stage);
        response.put("baseMinutes", baseMinutes);
        response.put("delayMinutes", delayMinutes);
        response.put("etaMinutes", etaMinutes);
        response.put("eta", DateTimeFormatter.ISO_INSTANT.format(eta));

        ctx.json(response);
    }

    private static int baseTransitMinutes(String hubId) {
        return 180 + Math.abs(hubId.hashCode()) % 240;
    }
}