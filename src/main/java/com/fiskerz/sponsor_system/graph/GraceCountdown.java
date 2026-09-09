package com.fiskerz.sponsor_system.graph;

import java.util.List;

/**
 * The arithmetic behind the abandonment countdown: formatting, urgency, and which warnings are due.
 *
 * <p>No Minecraft imports, so every boundary is unit testable. The caller owns the clock itself and all the
 * presentation; this only answers questions about a number of seconds.
 */
public final class GraceCountdown {
    /** Minutes remaining at which a chat warning is sent. Descending, because that is the order they fire in. */
    public static final List<Integer> WARNING_MINUTES = List.of(30, 15, 5, 1);

    private GraceCountdown() {}

    /** How much time is left, as {@code MM:SS}. Minutes are not padded; seconds always are. */
    public static String format(int secondsRemaining) {
        int clamped = Math.max(0, secondsRemaining);
        return (clamped / 60) + ":" + String.format("%02d", clamped % 60);
    }

    /** How alarmed the countdown should look. */
    public enum Urgency {
        /** More than five minutes left. */
        CALM,
        /** Five minutes or less. */
        URGENT,
        /** Under a minute. */
        CRITICAL
    }

    public static Urgency urgencyOf(int secondsRemaining) {
        if (secondsRemaining < 60) {
            return Urgency.CRITICAL;
        }
        return secondsRemaining <= 5 * 60 ? Urgency.URGENT : Urgency.CALM;
    }

    /**
     * Whether a chat warning is due on the tick that took the clock from {@code previousSeconds} to
     * {@code currentSeconds}.
     *
     * <p>Expressed as a crossing rather than an equality test on purpose. A player who logs in with 4 minutes left has
     * skipped past the 30, 15 and 5 minute marks while offline and should not be spammed with all of them; and a tick
     * that jumps several seconds must not step over a threshold silently.
     *
     * @return the threshold in minutes that was just crossed, or {@code 0} if none was
     */
    public static int warningCrossed(int previousSeconds, int currentSeconds) {
        for (int minutes : WARNING_MINUTES) {
            int threshold = minutes * 60;
            if (previousSeconds > threshold && currentSeconds <= threshold) {
                return minutes;
            }
        }
        return 0;
    }

    /** Rounds up to whole minutes, so a player with 1 second left is told "1 minute", never "0". */
    public static int minutesRemaining(int secondsRemaining) {
        return secondsRemaining <= 0 ? 0 : (secondsRemaining + 59) / 60;
    }

    /** Converts a configured minute count into seconds, saturating rather than overflowing. */
    public static int minutesToSeconds(int minutes) {
        long seconds = (long) minutes * 60L;
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }
}
