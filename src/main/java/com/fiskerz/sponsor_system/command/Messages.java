package com.fiskerz.sponsor_system.command;

import java.util.Locale;
import java.util.UUID;

import com.fiskerz.sponsor_system.graph.GraceCountdown;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorGraphException;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.TicketBudget;
import com.fiskerz.sponsor_system.server.ProfileLookup;
import com.fiskerz.sponsor_system.server.SponsorManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Builds every player-facing message.
 *
 * <p>Everything goes through {@link Component#translatable}; there is no hardcoded English in the Java sources. The
 * keys live in {@code assets/sponsorsystem/lang/en_us.json}.
 */
public final class Messages {
    private Messages() {}

    public static MutableComponent error(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.RED);
    }

    public static MutableComponent success(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent info(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
    }

    public static MutableComponent header(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GOLD);
    }

    /** A player name, highlighted so it stands out from the surrounding sentence. */
    public static Component name(String name) {
        return Component.literal(name).withStyle(ChatFormatting.WHITE);
    }

    public static Component name(SponsorEntry entry) {
        return name(entry.displayName());
    }

    /** A status word, coloured by what it means. Abandoned is struck through: they are hanging by nothing. */
    public static Component status(SponsorStatus status) {
        MutableComponent text = Component.translatable("sponsorsystem.status." + status.name().toLowerCase(Locale.ROOT));
        return switch (status) {
            case PENDING -> text.withStyle(ChatFormatting.YELLOW);
            case ACTIVE -> text.withStyle(ChatFormatting.GREEN);
            case REVOKED -> text.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH);
            case ABANDONED -> text.withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            case EXPIRED -> text.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH);
        };
    }

    /** "7 tickets remaining", or the unlimited wording for the root and operators. */
    public static MutableComponent ticketsRemaining(TicketBudget budget) {
        return budget.isUnlimited()
                ? info("sponsorsystem.tickets.unlimited")
                : info("sponsorsystem.tickets.remaining", budget.remaining(), budget.limit());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Clickable components
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A player's name, clickable to open their tree.
     *
     * <p>Two things about 1.21.1 specifically. {@link ClickEvent} here is a plain class taking
     * {@code (Action, String)} — it only becomes a sealed interface of records in 1.21.5. And the command string
     * <strong>must</strong> start with a slash: on everything from 1.19.1 to 1.21.4 the client silently ignores a
     * {@code run_command} value that does not, and the click simply does nothing.
     *
     * <p>The click targets the UUID form of the command rather than the name. Names change and can be reused; a tree
     * whose links break when somebody renames would be worse than a tree with no links at all.
     */
    public static MutableComponent clickableName(SponsorEntry entry, ChatFormatting colour, Component hover) {
        return Component.literal(entry.displayName())
                .withStyle(style -> style
                        .withColor(colour)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/invitetree uuid " + entry.getUuid()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    /** A plain, unclickable name, for a console or command-block source that cannot follow links. */
    public static MutableComponent plainName(SponsorEntry entry, ChatFormatting colour) {
        return Component.literal(entry.displayName()).withStyle(colour);
    }

    /** A clickable {@code [confirm]} that re-runs a command with its confirmation flag set. */
    public static MutableComponent confirmButton(String command) {
        return Component.translatable("sponsorsystem.action.confirm")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("sponsorsystem.action.confirm.hover"))));
    }

    /** The hover card shown over a name in the tree: UUID, status, and how many people back them. */
    public static Component hoverCard(SponsorManager manager, SponsorEntry entry) {
        int supporters = manager.graph().edgesTo(entry.getUuid()).size();
        int supported = manager.graph().edgesFrom(entry.getUuid()).size();
        return Component.empty()
                .append(Component.literal(entry.getUuid().toString()).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("\n"))
                .append(info("sponsorsystem.tree.hover", status(entry.getStatus()), supporters, supported));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The abandonment countdown
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The action-bar countdown line, coloured by how close it is.
     *
     * <p>Sent with {@code ServerPlayer#displayClientMessage(component, true)} — the boolean is the action-bar flag.
     * Exactly one component is built per abandoned online player per second.
     */
    public static MutableComponent graceActionBar(int secondsRemaining) {
        MutableComponent text = Component.translatable("sponsorsystem.grace.action_bar",
                GraceCountdown.format(secondsRemaining));
        return switch (GraceCountdown.urgencyOf(secondsRemaining)) {
            case CALM -> text.withStyle(ChatFormatting.YELLOW);
            case URGENT -> text.withStyle(ChatFormatting.RED);
            case CRITICAL -> text.withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        };
    }

    /** The chat warning sent when the clock crosses a threshold. Chat persists; the action bar does not. */
    public static MutableComponent graceWarning(int minutesRemaining) {
        MutableComponent text = Component.translatable("sponsorsystem.grace.warning", minutesRemaining);
        return minutesRemaining <= 5
                ? text.withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                : text.withStyle(ChatFormatting.RED);
    }

    // ---------------------------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------------------------
    // Shared failure wording
    // ---------------------------------------------------------------------------------------------------------------

    /** Turns a failed profile lookup into something the player can act on. */
    public static Component lookupFailure(ProfileLookup lookup, String targetName) {
        return switch (lookup.result()) {
            case INVALID_NAME -> error("sponsorsystem.error.lookup.invalid_name", name(targetName));
            case NOT_FOUND -> error("sponsorsystem.error.lookup.not_found", name(targetName));
            case OFFLINE_MODE_BLOCKED -> error("sponsorsystem.error.lookup.offline_mode");
            default -> error("sponsorsystem.error.lookup.unavailable", name(targetName));
        };
    }

    /**
     * Turns a refusal from the graph into something the player can act on.
     *
     * <p>None of these say or imply that one supporter matters more than another — that distinction does not exist.
     */
    public static Component refusal(SponsorManager manager, SponsorGraphException exception, String targetName) {
        return switch (exception.getReason()) {
            case SELF_SUPPORT -> error("sponsorsystem.error.self_support");
            case SUPPORTER_NOT_IN_GRAPH -> error("sponsorsystem.invite.not_in_graph");
            case SUPPORTER_REVOKED -> error("sponsorsystem.error.self_revoked");
            case ALREADY_IN_GRAPH -> error("sponsorsystem.invite.already_here", name(targetName));
            case NOT_IN_GRAPH -> error("sponsorsystem.sponsor.not_here", name(targetName));
            case DUPLICATE_EDGE -> error("sponsorsystem.sponsor.already_backing", name(targetName));
            case NO_TICKETS_LEFT -> error("sponsorsystem.tickets.none_left",
                    manager == null ? 0 : manager.budgetFor(exception.getSubject(), null).limit());
            case NO_SUCH_EDGE -> error("sponsorsystem.withdraw.not_yours", name(targetName));
            case SPONSORSHIP_TOO_YOUNG -> error("sponsorsystem.withdraw.too_young", name(targetName));
        };
    }

    /** How much longer a sponsorship must be held before it can be withdrawn, rounded up to whole minutes. */
    public static long minutesUntilWithdrawable(long createdAt, long minDurationMillis, long now) {
        long remaining = createdAt + minDurationMillis - now;
        return remaining <= 0L ? 0L : (remaining + 59_999L) / 60_000L;
    }

    /** Renders a UUID for the debug dump. */
    public static String shortUuid(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }
}
