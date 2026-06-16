package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class CflyCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("cfly")
                .executes(ctx -> toggleFlight(ctx.getSource(), false))
                .then(literal("jump")
                    .executes(ctx -> toggleFlight(ctx.getSource(), true))
                )
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource()))
                )
        );
    }

    private static int toggleFlight(FabricClientCommandSource source, boolean jump) {
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

        abilities.flying = !abilities.flying;

        // Sync abilities to server
        if (client.player.connection != null) {
            client.player.connection.send(new ServerboundPlayerAbilitiesPacket(abilities));
        }

        if (abilities.flying) {
            if (jump && client.player.onGround()) {
                client.player.jumpFromGround();
                source.sendFeedback(Component.translatable("client-tools.cfly.enabled_jump"));
            } else {
                source.sendFeedback(Component.translatable("client-tools.cfly.enabled"));
            }
        } else {
            source.sendFeedback(Component.translatable("client-tools.cfly.disabled"));
        }

        return 1;
    }

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

        return 1;
    }
}
