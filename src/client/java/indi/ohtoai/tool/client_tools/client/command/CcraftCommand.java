package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.craft.CcraftState;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.craft.RecipeChainAnalyzer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.phys.BlockHitResult;

import java.util.*;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class CcraftCommand {

    // --- Suggestion providers ---

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

    private static final SuggestionProvider<FabricClientCommandSource> COORD_X_SUGGESTIONS =
        (ctx, builder) -> {
            BlockPos pos = getAimedBlockPos();
            if (pos != null) builder.suggest(String.valueOf(pos.getX()));
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> COORD_Y_SUGGESTIONS =
        (ctx, builder) -> {
            BlockPos pos = getAimedBlockPos();
            if (pos != null) builder.suggest(String.valueOf(pos.getY()));
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> COORD_Z_SUGGESTIONS =
        (ctx, builder) -> {
            BlockPos pos = getAimedBlockPos();
            if (pos != null) builder.suggest(String.valueOf(pos.getZ()));
            return builder.buildFuture();
        };

    // --- Registration ---

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("ccraft")
                // /ccraft source <item>
                .then(literal("source")
                    .then(argument("item", StringArgumentType.greedyString())
                        .suggests(ITEM_SUGGESTIONS)
                        .executes(ctx -> setSource(ctx.getSource(),
                            StringArgumentType.getString(ctx, "item")))
                    )
                )
                // /ccraft product <item>
                .then(literal("product")
                    .then(argument("item", StringArgumentType.greedyString())
                        .suggests(ITEM_SUGGESTIONS)
                        .executes(ctx -> setProduct(ctx.getSource(),
                            StringArgumentType.getString(ctx, "item")))
                    )
                )
                // /ccraft station (aimed)
                .then(literal("station")
                    .executes(ctx -> setStationAimed(ctx.getSource()))
                    .then(argument("x", IntegerArgumentType.integer())
                        .suggests(COORD_X_SUGGESTIONS)
                        .then(argument("y", IntegerArgumentType.integer())
                            .suggests(COORD_Y_SUGGESTIONS)
                            .then(argument("z", IntegerArgumentType.integer())
                                .suggests(COORD_Z_SUGGESTIONS)
                                .executes(ctx -> setStationManual(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"),
                                    IntegerArgumentType.getInteger(ctx, "z")))
                            )
                        )
                    )
                )
                // /ccraft input (aimed)
                .then(literal("input")
                    .executes(ctx -> setInputAimed(ctx.getSource()))
                    .then(argument("x", IntegerArgumentType.integer())
                        .suggests(COORD_X_SUGGESTIONS)
                        .then(argument("y", IntegerArgumentType.integer())
                            .suggests(COORD_Y_SUGGESTIONS)
                            .then(argument("z", IntegerArgumentType.integer())
                                .suggests(COORD_Z_SUGGESTIONS)
                                .executes(ctx -> setInputManual(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"),
                                    IntegerArgumentType.getInteger(ctx, "z")))
                            )
                        )
                    )
                )
                // /ccraft output (aimed)
                .then(literal("output")
                    .executes(ctx -> setOutputAimed(ctx.getSource()))
                    .then(argument("x", IntegerArgumentType.integer())
                        .suggests(COORD_X_SUGGESTIONS)
                        .then(argument("y", IntegerArgumentType.integer())
                            .suggests(COORD_Y_SUGGESTIONS)
                            .then(argument("z", IntegerArgumentType.integer())
                                .suggests(COORD_Z_SUGGESTIONS)
                                .executes(ctx -> setOutputManual(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"),
                                    IntegerArgumentType.getInteger(ctx, "z")))
                            )
                        )
                    )
                )
                // /ccraft status
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource()))
                )
                // /ccraft clear
                .then(literal("clear")
                    .executes(ctx -> clearState(ctx.getSource()))
                )
                // /ccraft count <number|infinite>
                .then(literal("count")
                    .then(argument("value", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            builder.suggest("1");
                            builder.suggest("8");
                            builder.suggest("64");
                            builder.suggest("infinite");
                            return builder.buildFuture();
                        })
                        .executes(ctx -> setCount(ctx.getSource(),
                            StringArgumentType.getString(ctx, "value")))
                    )
                )
                // /ccraft stop
                .then(literal("stop")
                    .executes(ctx -> stopCraft(ctx.getSource()))
                )
                // /ccraft run
                .then(literal("run")
                    .executes(ctx -> runCraft(ctx.getSource()))
                )
        );
    }

    // ==================== Helpers ====================

    private static BlockPos getAimedBlockPos() {
        Minecraft client = Minecraft.getInstance();
        if (client.hitResult instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private static Item parseItem(String input) {
        ResourceLocation id = ResourceLocation.tryParse(input);
        if (id == null) id = ResourceLocation.tryParse("minecraft:" + input);
        if (id == null) return null;
        return BuiltInRegistries.ITEM.get(id);
    }

    // ==================== Individual subcommands ====================

    private static int setSource(FabricClientCommandSource source, String itemInput) {
        Item item = parseItem(itemInput);
        if (item == null) { source.sendFeedback(Component.literal("§cUnknown item: " + itemInput)); return 0; }
        CcraftState.setSourceItem(item);
        source.sendFeedback(Component.literal("§aSource set to: §e" + BuiltInRegistries.ITEM.getKey(item)));
        return 1;
    }

    private static int setProduct(FabricClientCommandSource source, String itemInput) {
        Item item = parseItem(itemInput);
        if (item == null) { source.sendFeedback(Component.literal("§cUnknown item: " + itemInput)); return 0; }
        CcraftState.setProductItem(item);
        source.sendFeedback(Component.literal("§aProduct set to: §e" + BuiltInRegistries.ITEM.getKey(item)));
        return 1;
    }

    private static int setStationAimed(FabricClientCommandSource source) {
        BlockPos pos = getAimedBlockPos();
        if (pos == null) { source.sendFeedback(Component.literal("§cNot looking at a block.")); return 0; }
        CcraftState.setStationPos(pos);
        source.sendFeedback(Component.literal("§aStation set to: §e" + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        return 1;
    }

    private static int setStationManual(FabricClientCommandSource source, int x, int y, int z) {
        CcraftState.setStationPos(new BlockPos(x, y, z));
        source.sendFeedback(Component.literal("§aStation set to: §e" + x + " " + y + " " + z));
        return 1;
    }

    private static int setInputAimed(FabricClientCommandSource source) {
        BlockPos pos = getAimedBlockPos();
        if (pos == null) { source.sendFeedback(Component.literal("§cNot looking at a block.")); return 0; }
        CcraftState.setInputPos(pos);
        source.sendFeedback(Component.literal("§aInput box set to: §e" + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        return 1;
    }

    private static int setInputManual(FabricClientCommandSource source, int x, int y, int z) {
        CcraftState.setInputPos(new BlockPos(x, y, z));
        source.sendFeedback(Component.literal("§aInput box set to: §e" + x + " " + y + " " + z));
        return 1;
    }

    private static int setOutputAimed(FabricClientCommandSource source) {
        BlockPos pos = getAimedBlockPos();
        if (pos == null) { source.sendFeedback(Component.literal("§cNot looking at a block.")); return 0; }
        CcraftState.setOutputPos(pos);
        source.sendFeedback(Component.literal("§aOutput box set to: §e" + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        return 1;
    }

    private static int setOutputManual(FabricClientCommandSource source, int x, int y, int z) {
        CcraftState.setOutputPos(new BlockPos(x, y, z));
        source.sendFeedback(Component.literal("§aOutput box set to: §e" + x + " " + y + " " + z));
        return 1;
    }

    private static int setCount(FabricClientCommandSource source, String value) {
        if (value.equalsIgnoreCase("infinite")) {
            CcraftState.setRepeatCount(-1);
            source.sendFeedback(Component.literal("§aTarget count set to: §einfinite §7(until materials run out or box full)"));
        } else {
            try {
                int count = Integer.parseInt(value);
                if (count < 1) {
                    source.sendFeedback(Component.literal("§cCount must be >= 1, or use §einfinite"));
                    return 0;
                }
                CcraftState.setRepeatCount(count);
                source.sendFeedback(Component.literal("§aTarget count set to: §e" + count + " §7(max final products to craft)"));
            } catch (NumberFormatException e) {
                source.sendFeedback(Component.literal("§cInvalid count. Use a number or §einfinite"));
                return 0;
            }
        }
        return 1;
    }

    // ==================== Status / Clear ====================

    private static int showStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("§6--- /ccraft Status ---"));
        printParam(source, "Source", CcraftState.getSourceItem());
        printParam(source, "Product", CcraftState.getProductItem());
        printPos(source, "Station", CcraftState.getStationPos());
        printPos(source, "Input box", CcraftState.getInputPos());
        printPos(source, "Output box", CcraftState.getOutputPos());
        int count = CcraftState.getRepeatCount();
        source.sendFeedback(Component.literal(
            count == -1 ? "§aTarget count: §einfinite"
                        : "§aTarget count: §e" + count));
        if (CcraftState.isReady()) {
            source.sendFeedback(Component.literal("§a§lReady! Use §e/ccraft run"));
        } else {
            source.sendFeedback(Component.literal("§eMissing: " + CcraftState.getMissingParams()));
        }
        return 1;
    }

    private static void printParam(FabricClientCommandSource source, String label, Item item) {
        source.sendFeedback(Component.literal(
            item != null ? "§a" + label + ": §e" + BuiltInRegistries.ITEM.getKey(item)
                         : "§7" + label + ": §7(not set)"
        ));
    }

    private static void printPos(FabricClientCommandSource source, String label, BlockPos pos) {
        source.sendFeedback(Component.literal(
            pos != null ? "§a" + label + ": §e" + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        : "§7" + label + ": §7(not set)"
        ));
    }

    private static int clearState(FabricClientCommandSource source) {
        CcraftState.clear();
        source.sendFeedback(Component.literal("§a/ccraft state cleared."));
        return 1;
    }

    // ==================== Run ====================

    private static int runCraft(FabricClientCommandSource source) {
        if (!CcraftState.isReady()) {
            source.sendFeedback(Component.literal("§cMissing parameters: " + CcraftState.getMissingParams()));
            return 0;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) { source.sendFeedback(Component.literal("§cNot connected to a world.")); return 0; }

        // Auto-find crafting table if not set
        BlockPos station = CcraftState.getStationPos();
        if (station == null) {
            station = CraftingExecutor.findNearestCraftingTable(client, CcraftState.getInputPos(), 16);
            if (station == null) {
                source.sendFeedback(Component.literal("§cNo crafting table found nearby. Use §e/ccraft station <x> <y> <z>"));
                return 0;
            }
            source.sendFeedback(Component.literal("§7Auto-detected crafting table at: §e"
                + station.getX() + " " + station.getY() + " " + station.getZ()));
        }

        Item sourceItem = CcraftState.getSourceItem();
        Item productItem = CcraftState.getProductItem();
        BlockPos input = CcraftState.getInputPos();
        BlockPos output = CcraftState.getOutputPos();
        RecipeManager rm = client.level.getRecipeManager();
        HolderLookup.Provider reg = client.level.registryAccess();

        RecipeChainAnalyzer.RecipeChain chain = RecipeChainAnalyzer.analyze(sourceItem, productItem, rm, reg);
        if (chain == null) {
            source.sendFeedback(Component.literal("§cNo crafting chain found from §e"
                + BuiltInRegistries.ITEM.getKey(sourceItem) + " §cto §e"
                + BuiltInRegistries.ITEM.getKey(productItem)));
            return 0;
        }
        if (chain.steps().isEmpty()) {
            source.sendFeedback(Component.literal("§eSource and product are the same item."));
            return 0;
        }

        source.sendFeedback(Component.literal("§6=== Crafting Chain ==="));
        for (int i = 0; i < chain.steps().size(); i++) {
            source.sendFeedback(Component.literal("§b  Step " + (i + 1) + ": §f" + chain.steps().get(i).toString()));
        }
        source.sendFeedback(Component.literal("§6======================"));
        source.sendFeedback(Component.literal("§7Station: §f" + station.getX() + " " + station.getY() + " " + station.getZ()
            + "  §7Input: §f" + input.getX() + " " + input.getY() + " " + input.getZ()
            + "  §7Output: §f" + output.getX() + " " + output.getY() + " " + output.getZ()));

        if (client.player != null && client.player.blockPosition().distSqr(station) > 36) {
            source.sendFeedback(Component.literal("§eMove closer to the crafting table."));
        }

        int repeatCount = CcraftState.getRepeatCount();
        String countLabel = repeatCount == -1 ? "infinite" : String.valueOf(repeatCount);
        CraftingExecutor.getInstance().start(chain.steps(), station, input, output, productItem, repeatCount);
        source.sendFeedback(Component.literal("§a§lExecuting crafting chain... (max " + countLabel + " final products)"));
        return 1;
    }

    // ==================== Stop ====================

    private static int stopCraft(FabricClientCommandSource source) {
        if (CraftingExecutor.getInstance().isRunning()) {
            CraftingExecutor.getInstance().stop();
            source.sendFeedback(Component.literal("§cCrafting stopped."));
        } else {
            source.sendFeedback(Component.literal("§7No crafting is currently running."));
        }
        return 1;
    }
}
