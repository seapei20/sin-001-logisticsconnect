package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

public class AlertBotApp {

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7054);

        app.get("/health", ctx -> ctx.result("OK"));

        // TODO (Posts proactive delay notifications to public transit social media pages (simulated).)
        // Mechanism: Outbound webhook, simulated social post
    }
}

// MQ TODO (stretch goal): subscribes to ActiveMQ topic MqConfig.TOPIC at MqConfig.BROKER_URL (see co.wethinkcode.logisticsconnect.mq.MqConfig)
