package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.shop.ShopExecutor;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

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

    private static final SuggestionProvider<FabricClientCommandSource> ITEM_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            // Chinese common names first
            for (String name : ShopExecutor.getChineseItemNames()) {
                if (name.toLowerCase().contains(remaining)) {
                    builder.suggest(name);
                }
            }
            // Minecraft item IDs (limit to reasonable count)
            BuiltInRegistries.ITEM.keySet().stream()
                .map(ResourceLocation::toString)
                .filter(id -> id.toLowerCase().contains(remaining))
                .limit(30)
                .forEach(builder::suggest);
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> COUNT_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"all", "1", "8", "16", "32", "64", "128", "256", "512", "1024"}) {
                if (s.startsWith(remaining)) builder.suggest(s);
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("cbuy")
                .then(argument("item", StringArgumentType.string())
                    .suggests(ITEM_SUGGESTIONS)
                    .then(argument("count", StringArgumentType.word())
                        .suggests(COUNT_SUGGESTIONS)
                        .executes(ctx -> executeBuy(ctx.getSource(),
                            StringArgumentType.getString(ctx, "item"),
                            StringArgumentType.getString(ctx, "count"))))
                    .executes(ctx -> executeBuy(ctx.getSource(),
                        StringArgumentType.getString(ctx, "item"), "all")))
        );
    }

    private static int executeBuy(FabricClientCommandSource source, String itemName, String countStr) {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null || client.player.connection == null) {
            source.sendFeedback(Component.translatable("client-tools.shop.not_connected"));
            return 0;
        }

        // Resolve item
        Item item = ShopExecutor.resolveItem(itemName);
        if (item == null) {
            source.sendFeedback(Component.translatable("client-tools.shop.unknown_item", itemName));
            return 0;
        }

        // Parse count
        int count;
        if ("all".equalsIgnoreCase(countStr)) {
            count = -1; // -1 = max/all
        } else {
            try {
                count = Integer.parseInt(countStr);
                if (count <= 0) {
                    source.sendFeedback(Component.translatable("client-tools.shop.count_invalid", countStr));
                    return 0;
                }
            } catch (NumberFormatException e) {
                source.sendFeedback(Component.translatable("client-tools.shop.count_invalid", countStr));
                return 0;
            }
        }

        // Check for conflicts
        if (CraftingExecutor.getInstance().isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.shop.crafting_running"));
            return 0;
        }
        if (ShopExecutor.getInstance().isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.shop.already_running"));
            return 0;
        }

        ShopExecutor.getInstance().startBuy(item, count);
        String countDisplay = count < 0 ? "all" : String.valueOf(count);
        source.sendFeedback(Component.translatable("client-tools.shop.buy_start",
            item.getDescription().getString(), countDisplay));
        return 1;
    }
}
