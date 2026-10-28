package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes stage changes to the shared {@code package-status-topic}.
 *
 * <p>Publishing is best-effort and never blocks a stage change from being recorded: the
 * in-memory stage map is the source of truth for this service, so a broker outage costs
 * consumers freshness rather than the correctness of the state itself.
 * {@link #publish} reports whether the message reached the topic so the caller can surface
 * the degradation instead of swallowing it.
 *
 * <p>Each publish is bounded by {@link #PUBLISH_TIMEOUT_SECONDS}. The failover transport
 * reconnects in the background, and a send issued while it is disconnected waits on the
 * transport's reconnect lock for as long as the outage lasts — the broker's own send
 * timeout does not cover that wait, so the bound is applied here to keep one unavailable
 * broker from pinning request threads.
 */
public class StagePublisher {

    private static final int PUBLISH_TIMEOUT_SECONDS = 3;

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Single thread: publishes are serialized, so the JMS session and producer below are
     * only ever touched by this one thread.
     */
    private final ExecutorService publisher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stage-publisher");
        thread.setDaemon(true);
        return thread;
    });

    private Connection connection;
    private Session session;
    private MessageProducer producer;

    public StagePublisher() {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_FAILOVER_URL);
        factory.setWatchTopicAdvisories(false);
        this.connectionFactory = factory;
    }

    /**
     * Publishes a single stage-change message.
     *
     * @return true if the broker accepted the message within the timeout, false if it was
     *         unreachable. A false return does not invalidate the stage change itself, and
     *         a message that times out may still reach consumers once the broker recovers.
     */
    public boolean publish(PackageStatusMessage message) {
        Future<Boolean> result = publisher.submit(() -> send(message));
        try {
            return result.get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            result.cancel(true);
            System.out.println("MQ publish timed out after " + PUBLISH_TIMEOUT_SECONDS
                    + "s for " + message.hubId() + ", stage change recorded but not broadcast");
            return false;
        } catch (Exception e) {
            System.out.println("MQ publish failed for " + message.hubId() + ": " + e.getMessage());
            return false;
        }
    }

    private boolean send(PackageStatusMessage message) {
        try {
            ensureProducer();
            TextMessage jmsMessage = session.createTextMessage(mapper.writeValueAsString(message));
            producer.send(jmsMessage);
            return true;
        } catch (JMSException | JsonProcessingException e) {
            // Drop the broken session so the next attempt rebuilds it.
            closeQuietly();
            System.out.println("MQ publish failed for " + message.hubId() + ": " + e.getMessage());
            return false;
        }
    }

    private void ensureProducer() throws JMSException {
        if (producer != null) {
            return;
        }
        connection = connectionFactory.createConnection();
        connection.setExceptionListener(e ->
                System.out.println("MQ connection interrupted, failover transport will restore it: "
                        + e.getMessage()));
        connection.start();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic(MqConfig.TOPIC);
        producer = session.createProducer(topic);
        System.out.println("Publishing to " + MqConfig.TOPIC + " at " + MqConfig.BROKER_URL);
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException ignored) {
                // Already failing; the next publish rebuilds the session.
            }
        }
        producer = null;
        session = null;
        connection = null;
    }
}
