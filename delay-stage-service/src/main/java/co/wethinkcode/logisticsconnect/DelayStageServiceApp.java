package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DelayStageServiceApp {

    private static final ConcurrentHashMap<String, Integer> stages = new ConcurrentHashMap<>();

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

        try {
            SetStageRequest request = ctx.bodyAsClass(SetStageRequest.class);
            if (request.stage() < 0 || request.stage() > 8) {
                ctx.status(400).json(Map.of("error", "Delay stage must be between 0 and 8"));
                return;
            }
            stages.put(hubId, request.stage());
            // MQ TODO (stage 3): publish {hubId, stage, timestamp} to MqConfig.TOPIC on change
            ctx.json(Map.of("hubId", hubId, "stage", request.stage()));
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid body, expected {\"stage\": N}: " + e.getMessage()));
        }
    }

    public record SetStageRequest(int stage) {}
}