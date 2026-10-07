package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DelayStageServiceApp {

    private static final ConcurrentHashMap<String, Integer> stages = new ConcurrentHashMap<>();
    private static final StagePublisher publisher = new StagePublisher();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7052);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/delay-stage", DelayStageServiceApp::listStages);
        app.get("/delay-stage/{hubId}", DelayStageServiceApp::getStage);
        app.post("/delay-stage/{hubId}", DelayStageServiceApp::setStage);
    }

    private static void listStages(Context ctx) {
        ctx.json(stages.entrySet().stream()
                .map(e -> Map.of("hubId", e.getKey(), "stage", e.getValue()))
                .toList());
    }

    private static void getStage(Context ctx) {
        String hubId = ctx.pathParam("hubId");
        ctx.json(Map.of("hubId", hubId, "stage", stages.getOrDefault(hubId, 0)));
    }

    private static void setStage(Context ctx) {
        String hubId = ctx.pathParam("hubId");

        int requestedStage;
        try {
            requestedStage = ctx.bodyAsClass(SetStageRequest.class).stage();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid body, expected {\"stage\": N}: " + e.getMessage()));
            return;
        }
        if (requestedStage < 0 || requestedStage > 8) {
            ctx.status(400).json(Map.of("error", "Delay stage must be between 0 and 8"));
            return;
        }

        Integer previous = stages.put(hubId, requestedStage);
        // Only a real transition is worth broadcasting: a repeat of the current stage would
        // tell consumers nothing and re-stamp the timestamp for no reason.
        boolean changed = previous == null || previous != requestedStage;
        boolean published = changed && publisher.publish(PackageStatusMessage.of(hubId, requestedStage));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hubId", hubId);
        response.put("stage", requestedStage);
        response.put("previousStage", previous == null ? 0 : previous);
        response.put("published", published);
        ctx.json(response);
    }

    public record SetStageRequest(int stage) {}
}