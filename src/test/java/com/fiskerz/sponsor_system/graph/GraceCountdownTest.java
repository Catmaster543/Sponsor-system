package com.fiskerz.sponsor_system.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fiskerz.sponsor_system.graph.GraceCountdown.Urgency;

/**
 * The countdown arithmetic: formatting, urgency bands, and warning thresholds.
 */
class GraceCountdownTest {
    @Nested
    @DisplayName("formatting")
    class Formatting {
        @Test
        @DisplayName("renders MM:SS with padded seconds")
        void formatsMinutesAndSeconds() {
            assertEquals("30:00", GraceCountdown.format(30 * 60));
            assertEquals("5:09", GraceCountdown.format(5 * 60 + 9));
            assertEquals("0:59", GraceCountdown.format(59));
            assertEquals("1:00", GraceCountdown.format(60));
        }

        @Test
        @DisplayName("never shows a negative clock")
        void clampsNegatives() {
            assertEquals("0:00", GraceCountdown.format(0));
            assertEquals("0:00", GraceCountdown.format(-30));
        }

        @Test
        @DisplayName("long windows keep counting in minutes rather than rolling over")
        void handlesLongWindows() {
            assertEquals("90:00", GraceCountdown.format(90 * 60));
        }
    }

    @Nested
    @DisplayName("urgency")
    class UrgencyBands {
        @Test
        @DisplayName("calm above five minutes")
        void calmAboveFive() {
            assertEquals(Urgency.CALM, GraceCountdown.urgencyOf(30 * 60));
            assertEquals(Urgency.CALM, GraceCountdown.urgencyOf(5 * 60 + 1));
        }

        @Test
        @DisplayName("urgent at five minutes and below")
        void urgentAtFive() {
            assertEquals(Urgency.URGENT, GraceCountdown.urgencyOf(5 * 60));
            assertEquals(Urgency.URGENT, GraceCountdown.urgencyOf(60));
        }

        @Test
        @DisplayName("critical under a minute")
        void criticalUnderOne() {
            assertEquals(Urgency.CRITICAL, GraceCountdown.urgencyOf(59));
            assertEquals(Urgency.CRITICAL, GraceCountdown.urgencyOf(0));
        }
    }

    @Nested
    @DisplayName("warning thresholds")
    class Warnings {
        @Test
        @DisplayName("fires exactly on the tick that crosses a threshold")
        void firesOnCrossing() {
            assertEquals(30, GraceCountdown.warningCrossed(30 * 60 + 1, 30 * 60));
            assertEquals(15, GraceCountdown.warningCrossed(15 * 60 + 1, 15 * 60));
            assertEquals(5, GraceCountdown.warningCrossed(5 * 60 + 1, 5 * 60));
            assertEquals(1, GraceCountdown.warningCrossed(61, 60));
        }

        @Test
        @DisplayName("stays quiet on a tick that crosses nothing")
        void quietOtherwise() {
            assertEquals(0, GraceCountdown.warningCrossed(20 * 60, 20 * 60 - 1));
            assertEquals(0, GraceCountdown.warningCrossed(30 * 60, 30 * 60 - 1), "already past it");
        }

        @Test
        @DisplayName("a tick that skips over a threshold still reports it")
        void catchesSkippedThreshold() {
            // The clock should move one second at a time, but a lag spike must not swallow the warning.
            assertEquals(15, GraceCountdown.warningCrossed(16 * 60, 14 * 60));
        }

        @Test
        @DisplayName("reports only the highest threshold crossed in one step")
        void reportsHighestOnly() {
            // Someone logging in with 30 seconds left should not be handed all four warnings at once.
            assertEquals(30, GraceCountdown.warningCrossed(31 * 60, 30));
        }
    }

    @Nested
    @DisplayName("minute conversion")
    class Minutes {
        @Test
        @DisplayName("rounds up, so the last second still reads as a minute")
        void roundsUp() {
            assertEquals(1, GraceCountdown.minutesRemaining(1));
            assertEquals(1, GraceCountdown.minutesRemaining(60));
            assertEquals(2, GraceCountdown.minutesRemaining(61));
            assertEquals(0, GraceCountdown.minutesRemaining(0));
        }

        @Test
        @DisplayName("converts a configured window into seconds without overflowing")
        void convertsWithoutOverflow() {
            assertEquals(30 * 60, GraceCountdown.minutesToSeconds(30));
            assertEquals(0, GraceCountdown.minutesToSeconds(0));
            assertEquals(Integer.MAX_VALUE, GraceCountdown.minutesToSeconds(Integer.MAX_VALUE));
        }
    }
}
