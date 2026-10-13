package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Simulates the outbound webhook that would publish a delay notice to a transit page.
 *
 * <p>No network call is made — the point of stage 4 is deciding <em>when</em> to post, not
 * integrating a real social API. Each post is rendered as the text that would have been sent
 * and kept in a bounded history, so a run can be inspected over HTTP afterwards instead of
 * being scrolled for in a log file.
 *
 * <p>The bound on the history is deliberate: a long-running instance sees a stage message per
 * hub transition forever, and an unbounded list would be a slow leak in a service whose whole
 * job is to run continuously.
 */
public class SocialPoster {

    /** Bounded so a long-running instance does not accumulate posts forever. */
    private static final int HISTORY_LIMIT = 100;

    private final Deque<PostedAlert> history = new ArrayDeque<>();

    /**
     * Renders and records one post.
     *
     * @return the recorded post, so a caller can log or return the exact text that went out
     */
    public synchronized PostedAlert post(PackageStatusMessage status, int previousStage, String reason) {
        String text = render(status, previousStage, reason);
        PostedAlert posted = new PostedAlert(
                status.hubId(),
                status.stage(),
                previousStage,
                reason,
                text,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        if (history.size() == HISTORY_LIMIT) {
            history.removeFirst();
        }
        history.addLast(posted);

        System.out.println("Posted to transit page: " + text);
        return posted;
    }

    private String render(PackageStatusMessage status, int previousStage, String reason) {
        return "[LogisticsConnect] Hub " + status.hubId() + " delay stage " + previousStage + " -> "
                + status.stage() + " (" + reason + "). Expect delays to outgoing parcels; "
                + "check the hub page for details.";
    }

    /** Oldest first, so the output reads in the order things were posted. */
    public synchronized List<PostedAlert> history() {
        return new ArrayList<>(history);
    }
}
