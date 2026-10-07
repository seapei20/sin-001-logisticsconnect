package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Payload published to {@code package-status-topic} whenever a hub's delay stage changes.
 * Duplicated into each participating service's own source tree, alongside {@code MqConfig},
 * since these are independent Maven projects with no shared parent pom.
 */
public record PackageStatusMessage(String hubId, int stage, String timestamp) {

    public static PackageStatusMessage of(String hubId, int stage) {
        return new PackageStatusMessage(hubId, stage, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
    }
}
