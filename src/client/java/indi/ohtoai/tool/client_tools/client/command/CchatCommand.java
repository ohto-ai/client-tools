package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class CchatCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("cchat")
                .then(argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> sendChat(ctx.getSource(),
                        StringArgumentType.getString(ctx, "message")))
                )
        );
    }

    private static int sendChat(FabricClientCommandSource source, String message) {
        Minecraft client = Minecraft.getInstance();

        if (client.player != null && client.player.connection != null) {
            client.player.connection.sendChat(message);
            source.sendFeedback(Component.literal("§aSent: " + message));
        } else {
            source.sendFeedback(Component.literal("§cCannot send chat: not connected to a server."));
        }

        return 1;
    }
}
