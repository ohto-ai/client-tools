package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class CflyCommand {

    /**
     * When {@code true}, enabling flight (via /cfly or /cfly enable) will
     * also cause the player to jump off the ground.  Toggled via {@code /cfly jump}.
     */
    private static boolean autoJump = false;

    public static boolean isAutoJump() { return autoJump; }
    public static void setAutoJump(boolean v) { autoJump = v; }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("cfly")
                .executes(ctx -> toggleFlight(ctx.getSource()))
                .then(literal("enable")
                    .executes(ctx -> setFlight(ctx.getSource(), true))
                )
                .then(literal("disable")
                    .executes(ctx -> setFlight(ctx.getSource(), false))
                )
                .then(literal("jump")
                    .executes(ctx -> toggleAutoJump(ctx.getSource()))
                    .then(literal("enable")
                        .executes(ctx -> setAutoJumpExplicit(ctx.getSource(), true)))
                    .then(literal("disable")
                        .executes(ctx -> setAutoJumpExplicit(ctx.getSource(), false)))
                )
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource()))
                )
        );
    }

    /**
     * Toggle flight on/off (backward-compatible convenience).
     * When enabling flight, respects the {@link #autoJump} setting.
     */
    private static int toggleFlight(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.cfly.not_in_world"));
            return 1;
        }

        var abilities = client.player.getAbilities();

        if (!abilities.mayfly) {
            source.sendFeedback(Component.translatable("client-tools.cfly.not_allowed"));
            return 1;
        }

        boolean newState = !abilities.flying;
        abilities.flying = newState;

        // Sync abilities to server
        if (client.player.connection != null) {
            client.player.connection.send(new ServerboundPlayerAbilitiesPacket(abilities));
        }

        if (abilities.flying) {
            if (autoJump && client.player.onGround()) {
                client.player.jumpFromGround();
                source.sendFeedback(Component.translatable("client-tools.cfly.enabled_jump"));
            } else if (autoJump) {
                source.sendFeedback(Component.translatable("client-tools.cfly.enabled_autojump_on"));
            } else {
                source.sendFeedback(Component.translatable("client-tools.cfly.enabled"));
            }
        } else {
            source.sendFeedback(Component.translatable("client-tools.cfly.disabled"));
        }

        return 1;
    }

    /**
     * Explicitly set flight to the desired state.
     * When enabling, respects the {@link #autoJump} setting.
     * If already in the requested state, reports that instead.
     */
    private static int setFlight(FabricClientCommandSource source, boolean enable) {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.cfly.not_in_world"));
            return 1;
        }

        var abilities = client.player.getAbilities();

        if (!abilities.mayfly) {
            source.sendFeedback(Component.translatable("client-tools.cfly.not_allowed"));
            return 1;
        }

        if (abilities.flying == enable) {
            // Already in the desired state
            source.sendFeedback(Component.translatable(
                enable ? "client-tools.cfly.already_enabled" : "client-tools.cfly.already_disabled"));
            return 1;
        }

        abilities.flying = enable;

        if (client.player.connection != null) {
            client.player.connection.send(new ServerboundPlayerAbilitiesPacket(abilities));
        }

        if (enable) {
            if (autoJump && client.player.onGround()) {
                client.player.jumpFromGround();
                source.sendFeedback(Component.translatable("client-tools.cfly.enabled_jump"));
            } else if (autoJump) {
                source.sendFeedback(Component.translatable("client-tools.cfly.force_enabled_autojump_on"));
            } else {
                source.sendFeedback(Component.translatable("client-tools.cfly.force_enabled"));
            }
        } else {
            source.sendFeedback(Component.translatable("client-tools.cfly.force_disabled"));
        }

        return 1;
    }

    // ---- Auto-jump mode ----

    private static int toggleAutoJump(FabricClientCommandSource source) {
        autoJump = !autoJump;
        source.sendFeedback(Component.translatable(
            autoJump ? "client-tools.cfly.autojump_enabled" : "client-tools.cfly.autojump_disabled"));
        return 1;
    }

    private static int setAutoJumpExplicit(FabricClientCommandSource source, boolean enable) {
        if (autoJump == enable) {
            source.sendFeedback(Component.translatable(
                enable ? "client-tools.cfly.autojump_already_enabled" : "client-tools.cfly.autojump_already_disabled"));
            return 1;
        }
        autoJump = enable;
        source.sendFeedback(Component.translatable(
            enable ? "client-tools.cfly.autojump_enabled" : "client-tools.cfly.autojump_disabled"));
        return 1;
    }

    // ---- Status ----

    private static int showStatus(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.cfly.not_in_world"));
            return 1;
        }

        var abilities = client.player.getAbilities();
        String gamemode = client.player.isCreative() ? "creative" : "survival";

        source.sendFeedback(Component.translatable("client-tools.cfly.status_header"));
        source.sendFeedback(Component.translatable("client-tools.cfly.status_mayfly",
            abilities.mayfly ? "§atrue" : "§cfalse"));
        source.sendFeedback(Component.translatable("client-tools.cfly.status_flying",
            abilities.flying ? "§atrue" : "§7false"));
        source.sendFeedback(Component.translatable("client-tools.cfly.status_gamemode",
            gamemode));
        source.sendFeedback(Component.translatable("client-tools.cfly.status_autojump",
            autoJump ? "§aON" : "§7OFF"));

        return 1;
    }
}
