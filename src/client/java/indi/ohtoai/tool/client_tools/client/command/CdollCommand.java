package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.doll.DollPackManager;
import indi.ohtoai.tool.client_tools.client.doll.DollState;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * {@code /cdoll} — dynamically applies the Furina doll 3D model to arbitrary items.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code add <item>} — registers an item for doll model replacement</li>
 *   <li>{@code remove <item>} — unregisters an item</li>
 *   <li>{@code list} — shows current doll-ified items</li>
 *   <li>{@code clear} — removes all doll assignments</li>
 * </ul>
 */
public class CdollCommand {

    /** Suggests all registered item IDs. */
    private static final SuggestionProvider<FabricClientCommandSource> ITEM_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            BuiltInRegistries.ITEM.keySet().stream()
                .map(ResourceLocation::toString)
                .filter(id -> id.toLowerCase().contains(remaining))
                .limit(50)
                .forEach(builder::suggest);
            return builder.buildFuture();
        };

    /** Suggests currently doll-ified item IDs (for removal). */
    private static final SuggestionProvider<FabricClientCommandSource> DOLL_ITEM_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            DollState.getDollItems().stream()
                .filter(id -> id.toLowerCase().contains(remaining))
                .forEach(builder::suggest);
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"add", "remove", "list", "clear"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("cdoll")
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand"))))
                )
                .then(literal("add")
                    .then(argument("item", StringArgumentType.greedyString())
                        .suggests(ITEM_SUGGESTIONS)
                        .executes(ctx -> addDoll(ctx.getSource(),
                            StringArgumentType.getString(ctx, "item"))))
                )
                .then(literal("remove")
                    .then(argument("item", StringArgumentType.greedyString())
                        .suggests(DOLL_ITEM_SUGGESTIONS)
                        .executes(ctx -> removeDoll(ctx.getSource(),
                            StringArgumentType.getString(ctx, "item"))))
                )
                .then(literal("list")
                    .executes(ctx -> listDolls(ctx.getSource()))
                )
                .then(literal("clear")
                    .executes(ctx -> clearDolls(ctx.getSource()))
                )
        );
    }

    // --- Help ---

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.cdoll.help.header"));
        source.sendFeedback(Component.translatable("client-tools.cdoll.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.cdoll.help.add"));
        source.sendFeedback(Component.translatable("client-tools.cdoll.help.remove"));
        source.sendFeedback(Component.translatable("client-tools.cdoll.help.list"));
        source.sendFeedback(Component.translatable("client-tools.cdoll.help.clear"));
        source.sendFeedback(Component.translatable("client-tools.cdoll.help.example"));
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "add" -> source.sendFeedback(
                Component.translatable("client-tools.cdoll.help.add_detail"));
            case "remove" -> source.sendFeedback(
                Component.translatable("client-tools.cdoll.help.remove_detail"));
            case "list" -> source.sendFeedback(
                Component.translatable("client-tools.cdoll.help.list_detail"));
            case "clear" -> source.sendFeedback(
                Component.translatable("client-tools.cdoll.help.clear_detail"));
            default -> source.sendFeedback(
                Component.translatable("client-tools.cdoll.help.unknown_subcommand", subcommand));
        }
        return 1;
    }

    // --- Executors ---

    private static int addDoll(FabricClientCommandSource source, String rawItem) {
        String fullId = parseItemId(rawItem);
        if (fullId == null) {
            source.sendFeedback(Component.translatable("client-tools.cdoll.unknown_item", rawItem));
            return 1;
        }

        if (!DollState.addDollItem(fullId)) {
            source.sendFeedback(Component.translatable("client-tools.cdoll.already_added", fullId));
            return 1;
        }

        DollPackManager.applyChanges();
        source.sendFeedback(Component.translatable("client-tools.cdoll.added", fullId));
        return 1;
    }

    private static int removeDoll(FabricClientCommandSource source, String rawItem) {
        String fullId = parseItemId(rawItem);
        if (fullId == null) {
            source.sendFeedback(Component.translatable("client-tools.cdoll.unknown_item", rawItem));
            return 1;
        }

        if (!DollState.removeDollItem(fullId)) {
            source.sendFeedback(Component.translatable("client-tools.cdoll.not_found", fullId));
            return 1;
        }

        DollPackManager.applyChanges();
        source.sendFeedback(Component.translatable("client-tools.cdoll.removed", fullId));
        return 1;
    }

    private static int listDolls(FabricClientCommandSource source) {
        Set<String> items = DollState.getDollItems();
        if (items.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.cdoll.list_empty"));
            return 1;
        }
        source.sendFeedback(Component.translatable("client-tools.cdoll.list_header", items.size()));
        for (String item : items) {
            source.sendFeedback(Component.translatable("client-tools.cdoll.list_entry", item));
        }
        return 1;
    }

    private static int clearDolls(FabricClientCommandSource source) {
        DollState.clearAll();
        DollPackManager.applyChanges();
        source.sendFeedback(Component.translatable("client-tools.cdoll.cleared"));
        return 1;
    }

    // --- Item parsing (mirrors CcraftCommand.parseItem) ---

    /**
     * Parses a raw item string into a normalized full item ID.
     * Returns {@code null} if the item is unknown or invalid.
     *
     * <p>Supports:
     * <ul>
     *   <li>Full IDs: {@code minecraft:diamond}</li>
     *   <li>Bare names: {@code diamond} (resolved to {@code minecraft:diamond})</li>
     * </ul>
     */
    private static String parseItemId(String input) {
        ResourceLocation id = ResourceLocation.tryParse(input);
        if (id == null) {
            id = ResourceLocation.tryParse("minecraft:" + input);
        }
        if (id == null) return null;

        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR || item == null) return null;

        return id.toString();
    }
}
