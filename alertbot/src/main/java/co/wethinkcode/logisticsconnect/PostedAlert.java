package co.wethinkcode.logisticsconnect;

/**
 * One simulated social media post, as recorded by {@link SocialPoster}.
 *
 * @param hubId         hub the alert is about
 * @param stage         stage the hub has just moved to
 * @param previousStage stage it moved from, as last seen by this service
 * @param reason        why the post was raised — a threshold crossing or a recovery
 * @param text          the rendered post body, as it would have been published
 * @param postedAt      ISO-8601 instant the post was recorded
 */
public record PostedAlert(
        String hubId,
        int stage,
        int previousStage,
        String reason,
        String text,
        String postedAt) {
}
