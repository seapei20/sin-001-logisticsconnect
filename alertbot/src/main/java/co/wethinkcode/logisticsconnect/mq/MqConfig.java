package co.wethinkcode.logisticsconnect.mq;

/**
 * Shared by every producer/consumer service that talks to the "package-status-topic"
 * ActiveMQ topic. Duplicated into each participating service's own source tree,
 * since these are independent Maven projects with no shared parent pom.
 */
public final class MqConfig {

    public static final String BROKER_URL = "tcp://localhost:61616";
    public static final String TOPIC = "package-status-topic";

    /**
     * Allows the client to reconnect if the broker connection drops.
     */
    public static final String BROKER_FAILOVER_URL = "failover:(" + BROKER_URL + ")"
            + "?initialReconnectDelay=1000&maxReconnectDelay=10000&useExponentialBackOff=true";

    private MqConfig() {
    }
}

