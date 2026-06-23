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
 * Shared helpers for {@code /cbuy} and {@code /csell} commands.
 * Uses a single {@code greedyString} argument to support namespaced
 * item IDs (e.g. {@code minecraft:sand}) which contain {@code :}
 * — a character not allowed in regular {@code StringArgumentType.string()}.
 *
 * <p>Command syntax:
 * <pre>
 * /cbuy &lt;item&gt; [count]
 * /csell &lt;item&gt; [count]
 * </pre>
 * The count defaults to {@code all} when omitted. When present, the last
 * space-separated token is treated as the count if it is a number or {@code all}.
 */
final class ShopCommandHelper {

    private ShopCommandHelper() {}

    enum Mode { BUY, SELL }

    // --- Suggestion providers ---

    static final SuggestionProvider<FabricClientCommandSource> ITEM_SUGGESTIONS =
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

    // --- Registration ---

    /**
     * Registers the command with a single {@code greedyString} argument so that
     * namespaced item IDs (containing {@code :}) are parsed correctly.
     * The item name and optional count are extracted from the raw input string.
     */
    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, String literalName, Mode mode) {
        dispatcher.register(
            literal(literalName)
                .then(argument("itemAndCount", StringArgumentType.greedyString())
                    .suggests(ITEM_SUGGESTIONS)
                    .executes(ctx -> execute(ctx.getSource(),
                        StringArgumentType.getString(ctx, "itemAndCount"),
                        mode)))
        );
    }

    // --- Execution ---

    private static int execute(FabricClientCommandSource source, String input, Mode mode) {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null || client.player.connection == null) {
            source.sendFeedback(Component.translatable("client-tools.shop.not_connected"));
            return 0;
        }

        // --- Parse item name and count from the greedy string ---
        // The last space-separated token is treated as the count if it
        // is "all" (case-insensitive) or a positive integer.
        // Everything before it is the item name.
        // This allows item names to contain ':' (e.g. "minecraft:sand")
        // as well as Chinese characters.
        String itemName;
        String countStr = "all";

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.shop.unknown_item", ""));
            return 0;
        }

        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace > 0) {
            String lastToken = trimmed.substring(lastSpace + 1);
            if (isCountToken(lastToken)) {
                itemName = trimmed.substring(0, lastSpace).trim();
                countStr = lastToken;
            } else {
                // Last token is not a count — treat entire input as item name
                itemName = trimmed;
            }
        } else {
            itemName = trimmed;
        }

        if (itemName.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.shop.unknown_item", ""));
            return 0;
        }

        // --- Resolve item ---
        Item item = ShopExecutor.resolveItem(itemName);
        if (item == null) {
            source.sendFeedback(Component.translatable("client-tools.shop.unknown_item", itemName));
            return 0;
        }

        // --- Parse count ---
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

        // --- Check for conflicts ---
        if (CraftingExecutor.getInstance().isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.shop.crafting_running"));
            return 0;
        }
        if (ShopExecutor.getInstance().isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.shop.already_running"));
            return 0;
        }

        // --- Dispatch ---
        String countDisplay = count < 0 ? "all" : String.valueOf(count);
        String translationKey;
        if (mode == Mode.BUY) {
            ShopExecutor.getInstance().startBuy(item, count);
            translationKey = "client-tools.shop.buy_start";
        } else {
            ShopExecutor.getInstance().startSell(item, count);
            translationKey = "client-tools.shop.sell_start";
        }
        source.sendFeedback(Component.translatable(translationKey,
            item.getDescription().getString(), countDisplay));
        return 1;
    }

    /**
     * Returns true if the token looks like a count argument: "all" or a positive integer.
     */
    private static boolean isCountToken(String token) {
        if (token.equalsIgnoreCase("all")) return true;
        return token.matches("\\d+");
    }
}
