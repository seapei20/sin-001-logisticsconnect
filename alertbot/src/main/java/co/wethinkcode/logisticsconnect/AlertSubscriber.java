package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.Topic;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches {@code package-status-topic} and posts a public notice when a hub's delay stage
 * crosses {@link #ALERT_THRESHOLD_STAGE}, and a second one when it drops back.
 *
 * <p>Stage 3 gave this service a second consumer on the same topic: transit-service turns a
 * stage into an ETA, alertbot turns the same stage into a public post. Neither talks to
 * delay-stage-service, so a stage change reaches both without either knowing about the other.
 *
 * <p>Alerts are raised on <em>crossings</em>, not on level. A hub sitting at stage 6 has
 * already been announced; re-posting on every message while it stays there would turn one
 * disruption into a stream of duplicate notices. {@link #alerting} tracks which hubs are
 * currently over the line so a second post only happens when the hub recovers and later
 * crosses again.
 *
 * <p>The same two limits as transit-service apply, both inherent to a plain topic: a hub with
 * no message yet reads as stage 0, so a hub already above the threshold when this service
 * starts is silent until its next change; and a change published while this service is
 * disconnected is not replayed. {@code GET /alerts} shows what was actually posted rather
 * than leaving it to the logs.
 */
public class AlertSubscriber {

    /**
     * Chosen threshold: stage 4 or worse is announced publicly. Roughly matches the point
     * where a delay stops being an internal scheduling detail and starts being something a
     * member of the public would notice, and is a long enough run from the 0-3 stages that
     * ordinary jitter does not trip it.
     */
    public static final int ALERT_THRESHOLD_STAGE = 4;

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SocialPoster poster;

    private final ConcurrentHashMap<String, Integer> lastStageByHub = new ConcurrentHashMap<>();

    /** Hubs currently at or above the threshold, so a crossing is only posted once. */
    private final ConcurrentHashMap<String, Boolean> alerting = new ConcurrentHashMap<>();

    public AlertSubscriber(SocialPoster poster) {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_FAILOVER_URL);
        factory.setWatchTopicAdvisories(false);
        this.connectionFactory = factory;
        this.poster = poster;
    }

    /**
     * Connects and subscribes, retrying every 3s until the broker accepts, so the service
     * starts whether or not the broker is already up. Once connected, the failover transport
     * takes over: a broker that drops mid-run is reconnected and this subscription restored
     * without restarting the service.
     *
     * <p>Runs on a daemon thread so the HTTP server starts regardless of broker state.
     */
    public void startReconnecting() {
        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    subscribe();
                    return;
                } catch (JMSException e) {
                    System.out.println("MQ broker not reachable yet, retrying in 3s: " + e.getMessage());
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

    private void subscribe() throws JMSException {
        Connection connection = connectionFactory.createConnection();
        connection.setExceptionListener(e ->
                System.out.println("MQ connection interrupted, failover transport will resubscribe: "
                        + e.getMessage()));
        connection.start();

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic(MqConfig.TOPIC);
        MessageConsumer consumer = session.createConsumer(topic);
        consumer.setMessageListener(this::onMessage);

        System.out.println("Alerting on stage >= " + ALERT_THRESHOLD_STAGE
                + " via " + MqConfig.TOPIC + " at " + MqConfig.BROKER_URL);
    }

    private void onMessage(Message message) {
        try {
            PackageStatusMessage status = mapper.readValue(message.getBody(String.class), PackageStatusMessage.class);
            evaluate(status);
        } catch (Exception e) {
            System.out.println("Ignoring unparseable status message: " + e.getMessage());
        }
    }

    private void evaluate(PackageStatusMessage status) {
        String hubId = status.hubId();
        int stage = status.stage();
        boolean overThreshold = stage >= ALERT_THRESHOLD_STAGE;

        // compute() runs the mapping function under the bin lock for this key, so two
        // concurrent messages for the same hub cannot both read the same "previous" and
        // both conclude they were the one that crossed. The side effect has to happen
        // inside the lambda for that guarantee to hold, so "from" is carried out through a
        // holder rather than read from the map afterwards.
        int[] from = {0};
        lastStageByHub.compute(hubId, (key, seen) -> {
            from[0] = seen == null ? 0 : seen;

            if (overThreshold) {
                // putIfAbsent returns null only if nobody has announced this hub yet, i.e.
                // this is the upward crossing rather than a repeat while it stays high.
                if (alerting.putIfAbsent(key, Boolean.TRUE) == null) {
                    poster.post(status, from[0], "crossed alert threshold " + ALERT_THRESHOLD_STAGE);
                }
            } else if (alerting.remove(key) != null) {
                poster.post(status, from[0], "recovered below alert threshold " + ALERT_THRESHOLD_STAGE);
            }
            return stage;
        });

        System.out.println("Watched " + hubId + ": " + from[0] + " -> " + stage
                + (overThreshold ? " (over threshold)" : " (under threshold)")
                + " at " + status.timestamp());
    }

    public Map<String, Integer> snapshot() {
        return Map.copyOf(lastStageByHub);
    }
}
