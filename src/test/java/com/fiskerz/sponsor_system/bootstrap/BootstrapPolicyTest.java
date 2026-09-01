package com.fiskerz.sponsor_system.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the founder-bootstrap rules.
 *
 * <p>These branches decide whether a server sits with its whitelist off, so every one of them is worth a test: getting
 * this wrong either locks an owner out of a new server or leaves a live server open to anyone.
 */
class BootstrapPolicyTest {
    private static final String ANYONE = "";
    private static final boolean LOOPBACK = true;
    private static final boolean REMOTE = false;
    private static final boolean ENABLED = true;
    private static final boolean DISABLED = false;

    @Nested
    @DisplayName("initial state")
    class InitialState {
        private static final boolean NO_TREE = true;
        private static final boolean HAS_TREE = false;
        private static final boolean NO_WHITELIST = true;
        private static final boolean HAS_WHITELIST = false;

        @Test
        @DisplayName("a brand new server - no tree, no whitelist - waits for a founder")
        void brandNewServerBootstraps() {
            assertEquals(ServerState.BOOTSTRAP, BootstrapPolicy.initialState(NO_TREE, NO_WHITELIST, ENABLED));
        }

        @Test
        @DisplayName("a tree with anyone in it is established")
        void populatedTreeIsEstablished() {
            assertEquals(ServerState.ESTABLISHED, BootstrapPolicy.initialState(HAS_TREE, NO_WHITELIST, ENABLED));
        }

        @Test
        @DisplayName("an empty tree with bootstrap off is a hard lockout, not a bootstrap")
        void disabledMeansLockout() {
            assertEquals(ServerState.ESTABLISHED, BootstrapPolicy.initialState(NO_TREE, NO_WHITELIST, DISABLED));
        }

        @Test
        @DisplayName("installing on a server that already has a whitelist must NOT open it")
        void existingWhitelistIsNotABrandNewServer() {
            // The first-install case: sponsors.json does not exist yet, but whitelist.json is populated. Bootstrapping
            // here would turn that server's whitelist off and expose it.
            assertEquals(ServerState.ESTABLISHED, BootstrapPolicy.initialState(NO_TREE, HAS_WHITELIST, ENABLED));
        }

        @Test
        @DisplayName("a populated tree and whitelist is established")
        void fullyPopulatedIsEstablished() {
            assertEquals(ServerState.ESTABLISHED, BootstrapPolicy.initialState(HAS_TREE, HAS_WHITELIST, ENABLED));
        }
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {
        @Test
        @DisplayName("an unrestricted server lets the first player claim")
        void unrestrictedClaim() {
            assertEquals(BootstrapDecision.CLAIM, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Fiskerz", ANYONE, false, REMOTE));
        }

        @Test
        @DisplayName("a second player arriving after the claim is refused")
        void secondPlayerRefused() {
            assertEquals(BootstrapDecision.ALREADY_ESTABLISHED, BootstrapPolicy.evaluateClaim(
                    ServerState.ESTABLISHED, ENABLED, "Interloper", ANYONE, false, LOOPBACK));
        }

        @Test
        @DisplayName("state is checked before every other guard, so an established server never re-opens")
        void stateBeatsEveryOtherGuard() {
            // Every other input here says yes; the state alone must still refuse.
            assertEquals(BootstrapDecision.ALREADY_ESTABLISHED, BootstrapPolicy.evaluateClaim(
                    ServerState.ESTABLISHED, ENABLED, "Fiskerz", "Fiskerz", true, LOOPBACK));
        }

        @Test
        @DisplayName("bootstrap disabled refuses even in the bootstrap state")
        void disabledRefuses() {
            assertEquals(BootstrapDecision.DISABLED, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, DISABLED, "Fiskerz", ANYONE, false, LOOPBACK));
        }
    }

    @Nested
    @DisplayName("restrictToName")
    class RestrictToName {
        @Test
        @DisplayName("the named player may claim")
        void namedPlayerClaims() {
            assertEquals(BootstrapDecision.CLAIM, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Fiskerz", "Fiskerz", false, REMOTE));
        }

        @Test
        @DisplayName("anyone else is refused")
        void otherPlayerRefused() {
            assertEquals(BootstrapDecision.WRONG_NAME, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Interloper", "Fiskerz", false, LOOPBACK));
        }

        @Test
        @DisplayName("matching ignores case, so a mistyped config does not lock the owner out")
        void caseInsensitive() {
            assertEquals(BootstrapDecision.CLAIM, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Fiskerz", "fiskerz", false, REMOTE));
        }

        @Test
        @DisplayName("surrounding whitespace in the config is tolerated")
        void trimsConfiguredName() {
            assertEquals(BootstrapDecision.CLAIM, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Fiskerz", "  Fiskerz  ", false, REMOTE));
        }

        @Test
        @DisplayName("a blank restriction means anyone")
        void blankMeansAnyone() {
            assertEquals(BootstrapDecision.CLAIM, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Anybody", "   ", false, REMOTE));
        }

        @Test
        @DisplayName("a null player name is refused rather than throwing")
        void nullNameRefused() {
            assertEquals(BootstrapDecision.WRONG_NAME, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, null, "Fiskerz", false, REMOTE));
        }
    }

    @Nested
    @DisplayName("restrictToLoopback")
    class RestrictToLoopback {
        @Test
        @DisplayName("a local connection may claim")
        void localClaims() {
            assertEquals(BootstrapDecision.CLAIM, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Dev", ANYONE, true, LOOPBACK));
        }

        @Test
        @DisplayName("a remote connection is refused")
        void remoteRefused() {
            assertEquals(BootstrapDecision.NOT_LOOPBACK, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Dev", ANYONE, true, REMOTE));
        }

        @Test
        @DisplayName("remote connections are fine when the restriction is off")
        void remoteAllowedWhenOff() {
            assertEquals(BootstrapDecision.CLAIM, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Dev", ANYONE, false, REMOTE));
        }

        @Test
        @DisplayName("the name guard is checked before the loopback guard")
        void nameCheckedFirst() {
            assertEquals(BootstrapDecision.WRONG_NAME, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Interloper", "Fiskerz", true, REMOTE));
        }

        @Test
        @DisplayName("both guards together admit only the right player from the right place")
        void bothGuards() {
            assertEquals(BootstrapDecision.CLAIM, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Fiskerz", "Fiskerz", true, LOOPBACK));
            assertEquals(BootstrapDecision.NOT_LOOPBACK, BootstrapPolicy.evaluateClaim(
                    ServerState.BOOTSTRAP, ENABLED, "Fiskerz", "Fiskerz", true, REMOTE));
        }
    }

    @Nested
    @DisplayName("decision helpers")
    class Helpers {
        @Test
        @DisplayName("only CLAIM counts as a claim")
        void isClaim() {
            assertTrue(BootstrapDecision.CLAIM.isClaim());
            assertFalse(BootstrapDecision.DISABLED.isClaim());
            assertFalse(BootstrapDecision.WRONG_NAME.isClaim());
            assertFalse(BootstrapDecision.NOT_LOOPBACK.isClaim());
            assertFalse(BootstrapDecision.ALREADY_ESTABLISHED.isClaim());
        }

        @Test
        @DisplayName("an unset restriction is described as open")
        void describesOpenRestriction() {
            assertEquals("(anyone)", BootstrapPolicy.describeRestriction(""));
            assertEquals("(anyone)", BootstrapPolicy.describeRestriction(null));
            assertEquals("Fiskerz", BootstrapPolicy.describeRestriction(" Fiskerz "));
        }
    }
}
