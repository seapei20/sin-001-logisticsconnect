package co.wethinkcode.logisticsconnect;

/**
 * Payload received from {@code package-status-topic} whenever a hub's delay stage changes.
 * Duplicated into each participating service's own source tree, alongside {@code MqConfig},
 * since these are independent Maven projects with no shared parent pom. Byte-identical to
 * the producer's copy in {@code delay-stage-service}: the two must agree on the payload.
 */
public record PackageStatusMessage(String hubId, int stage, String timestamp) {
}
