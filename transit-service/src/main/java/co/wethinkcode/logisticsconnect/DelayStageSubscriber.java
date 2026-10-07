package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.Topic;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a local view of each hub's delay stage by subscribing to {@code package-status-topic},
 * replacing the synchronous REST call to delay-stage-service.
 *
 * <p>Stage state is replicated rather than queried, so this service reads the cache in
 * microseconds and an unavailable delay-stage-service no longer fails an ETA request. The
 * trade-off is freshness: a hub with no message yet reads as stage 0, and a stage change
 * published while this service is disconnected is not replayed. A durable topic plus a
 * persistent subscriber would close that gap if it ever mattered.
 */
public class DelayStageSubscriber {

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Integer> latestStageByHub = new ConcurrentHashMap<>();

    public DelayStageSubscriber() {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_FAILOVER_URL);
        factory.setWatchTopicAdvisories(false);
        this.connectionFactory = factory;
    }

    /**
     * Connects and subscribes, retrying every 3s until the broker accepts, so the service
     * starts whether or not the broker is already up. Once connected, the failover
     * transport takes over: a broker that drops mid-run is reconnected and this
     * subscription restored without restarting the service.
     *
     * <p>Runs on a daemon thread so the rest of the service starts regardless of broker state.
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

        System.out.println("Subscribed to " + MqConfig.TOPIC + " at " + MqConfig.BROKER_URL);
    }

    private void onMessage(Message message) {
        try {
            PackageStatusMessage status = mapper.readValue(message.getBody(String.class), PackageStatusMessage.class);
            Integer previous = latestStageByHub.put(status.hubId(), status.stage());
            System.out.println("Stage update " + status.hubId() + ": "
                    + (previous == null ? "none" : previous) + " -> " + status.stage()
                    + " at " + status.timestamp());
        } catch (Exception e) {
            System.out.println("Ignoring unparseable status message: " + e.getMessage());
        }
    }

    /** Latest stage seen for a hub; 0 when no message has arrived for it yet. */
    public int stageFor(String hubId) {
        return latestStageByHub.getOrDefault(hubId, 0);
    }

    public Map<String, Integer> snapshot() {
        return Map.copyOf(latestStageByHub);
    }
}
