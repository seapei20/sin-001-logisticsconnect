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
     * {@link #BROKER_URL} wrapped in ActiveMQ's failover transport: a client that loses the
     * connection retries on its own with backoff and restores its producer or subscription,
     * so consumers keep receiving after a broker restart. Derived from {@link #BROKER_URL}
     * rather than replacing it, leaving the documented broker URL as the single source of
     * truth. An initial connect still fails fast, so services retry startup separately.
     */
    public static final String BROKER_FAILOVER_URL = "failover:(" + BROKER_URL + ")"
            + "?initialReconnectDelay=1000&maxReconnectDelay=10000&useExponentialBackOff=true";

    private MqConfig() {
    }
}
