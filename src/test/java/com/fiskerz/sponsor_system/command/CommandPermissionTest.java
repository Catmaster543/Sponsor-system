package com.fiskerz.sponsor_system.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Locks in which commands are open to players and which are operator-only.
 *
 * <p>This builds the <em>real</em> command tree through the same registration code the mod uses, then asks each
 * top-level node's {@code requires} predicate directly. Brigadier filters the tree it sends to a client through those
 * same predicates, so a command that fails this test would also be missing from tab-completion for that player — which
 * is the behaviour this is really protecting.
 *
 * <p>{@link CommandSourceStack#hasPermission(int)} reads nothing but its own permission-level field, so a source can
 * be built here with nulls for the world and server and still answer correctly without a running game.
 */
class CommandPermissionTest {
    /** Commands any player must be able to run: backing someone, and stopping backing them. */
    private static final List<String> PLAYER_COMMANDS =
            List.of("invite", "sponsor", "uninvite", "unsponsor", "mysponsors");
    /** Commands only an operator may run. */
    private static final List<String> ADMIN_COMMANDS = List.of("invitetree", "sponsorship");

    private static CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void buildTree() {
        dispatcher = new CommandDispatcher<>();
        SponsorCommands.register(dispatcher);
    }

    private static CommandSourceStack sourceAtLevel(int level) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, level,
                "test", Component.literal("test"), null, null);
    }

    private static CommandNode<CommandSourceStack> node(String name) {
        CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(name);
        assertNotNull(node, "/" + name + " is not registered at all");
        return node;
    }

    @Test
    @DisplayName("the four support commands are open to ordinary players")
    void playerCommandsAreUngated() {
        CommandSourceStack player = sourceAtLevel(0);

        for (String name : PLAYER_COMMANDS) {
            assertTrue(node(name).canUse(player), "/" + name + " must be usable at permission level 0");
        }
    }

    @Test
    @DisplayName("operator commands are closed to ordinary players")
    void adminCommandsAreGated() {
        CommandSourceStack player = sourceAtLevel(0);

        for (String name : ADMIN_COMMANDS) {
            assertFalse(node(name).canUse(player), "/" + name + " must NOT be usable at permission level 0");
        }
    }

    @Test
    @DisplayName("operator commands are closed at levels 1 and 2 as well")
    void adminCommandsNeedLevelThree() {
        for (String name : ADMIN_COMMANDS) {
            assertFalse(node(name).canUse(sourceAtLevel(1)), "/" + name + " must not open up at level 1");
            assertFalse(node(name).canUse(sourceAtLevel(2)), "/" + name + " must not open up at level 2");
            assertTrue(node(name).canUse(sourceAtLevel(3)), "/" + name + " must be usable at level 3");
        }
    }

    @Test
    @DisplayName("an operator can still use the player commands")
    void adminKeepsPlayerCommands() {
        CommandSourceStack admin = sourceAtLevel(4);

        for (String name : PLAYER_COMMANDS) {
            assertTrue(node(name).canUse(admin), "/" + name + " must stay usable for operators");
        }
    }

    @Test
    @DisplayName("every /sponsorship subcommand is operator-only, including debug")
    void everySponsorshipSubcommandIsGated() {
        CommandNode<CommandSourceStack> sponsorship = node("sponsorship");
        CommandSourceStack player = sourceAtLevel(0);
        CommandSourceStack admin = sourceAtLevel(3);

        assertFalse(sponsorship.getChildren().isEmpty(), "the subcommands should exist to be checked");
        for (CommandNode<CommandSourceStack> child : sponsorship.getChildren()) {
            // The parent gate already blocks these, but a child that opened itself up would be a silent hole.
            assertFalse(sponsorship.canUse(player) && child.canUse(player),
                    "/sponsorship " + child.getName() + " must not be reachable at level 0");
            assertTrue(child.canUse(admin), "/sponsorship " + child.getName() + " must be usable at level 3");
        }
        assertTrue(sponsorship.getChildren().stream().anyMatch(child -> child.getName().equals("debug")),
                "/sponsorship debug must be registered");
    }

    @Test
    @DisplayName("/invites has been removed")
    void invitesIsGone() {
        org.junit.jupiter.api.Assertions.assertNull(dispatcher.getRoot().getChild("invites"),
                "/invites was superseded by /invitetree and should no longer exist");
    }

    @Test
    @DisplayName("uninvite and unsponsor are both registered, as aliases of one operation")
    void withdrawHasBothNames() {
        assertNotNull(dispatcher.getRoot().getChild("uninvite"));
        assertNotNull(dispatcher.getRoot().getChild("unsponsor"));
    }


    @Test
    @DisplayName("/mysponsors takes no arguments at all, so it can only ever be about the caller")
    void mySponsorsHasNoArguments() {
        CommandNode<CommandSourceStack> node = node("mysponsors");

        assertTrue(node.getChildren().isEmpty(),
                "any argument here would be a way to ask about somebody else, which is the one thing it must not do");
    }

    @Test
    @DisplayName("the withdraw commands both accept a confirm flag")
    void withdrawAcceptsConfirm() {
        for (String name : List.of("uninvite", "unsponsor")) {
            CommandNode<CommandSourceStack> player = node(name).getChild("player");
            assertNotNull(player, "/" + name + " should take a player argument");
            assertNotNull(player.getChild("confirm"),
                    "/" + name + " <player> confirm is what the clickable warning re-runs");
        }
    }

    @Test
    @DisplayName("/invitetree can be navigated by uuid")
    void inviteTreeHasUuidPath() {
        CommandNode<CommandSourceStack> uuid = node("invitetree").getChild("uuid");
        assertNotNull(uuid, "every clickable name in the tree targets /invitetree uuid <uuid>");
        assertNotNull(uuid.getChild("uuid"));
    }
}
