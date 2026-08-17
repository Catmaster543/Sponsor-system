package com.fiskerz.sponsor_system.command;

import java.util.Locale;

import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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

    /** A status word, coloured by what it means: pending is waiting, active is fine, revoked is gone. */
    public static Component status(SponsorStatus status) {
        ChatFormatting colour = switch (status) {
            case PENDING -> ChatFormatting.YELLOW;
            case ACTIVE -> ChatFormatting.GREEN;
            case REVOKED -> ChatFormatting.DARK_GRAY;
        };
        return Component.translatable("sponsorsystem.status." + status.name().toLowerCase(Locale.ROOT))
                .withStyle(colour);
    }
}
