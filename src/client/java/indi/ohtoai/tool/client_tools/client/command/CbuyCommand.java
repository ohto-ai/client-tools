package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * Registers the {@code /cbuy} command for buying items through a container shop GUI.
 *
 * <pre>
 * /cbuy &lt;item&gt; &lt;count&gt;
 *
 *   item  — item name or Minecraft ID (e.g. "sand", "沙子", "minecraft:sand")
 *   count — number to buy, or "all" for maximum
 * </pre>
 */
public class CbuyCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        ShopCommandHelper.register(dispatcher, "cbuy", ShopCommandHelper.Mode.BUY);
    }
}
