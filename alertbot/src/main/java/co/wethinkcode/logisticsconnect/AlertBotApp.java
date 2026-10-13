package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;

public class AlertBotApp {

    private static final SocialPoster poster = new SocialPoster();
    private static final AlertSubscriber subscriber = new AlertSubscriber(poster);

    public static void main(String[] args) {
        // A daemon thread, so the service is up and answering /health even with no broker.
        subscriber.startReconnecting();

        Javalin app = Javalin.create().start(7054);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/alerts", ctx -> ctx.json(poster.history()));
        app.get("/watched", ctx -> ctx.json(subscriber.snapshot()));
        app.get("/threshold", AlertBotApp::threshold);
    }

    private static void threshold(Context ctx) {
        ctx.json(Map.of(
                "alertAtOrAbove", AlertSubscriber.ALERT_THRESHOLD_STAGE,
                "posted", poster.history().size()));
    }
}
