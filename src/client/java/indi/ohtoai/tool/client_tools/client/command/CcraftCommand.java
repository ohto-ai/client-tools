package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.craft.CcraftHighlightRenderer;
import indi.ohtoai.tool.client_tools.client.craft.CcraftState;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.craft.MaterialPlanner;
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

    /** Suggests common show durations. */
    private static final SuggestionProvider<FabricClientCommandSource> DURATION_SUGGESTIONS =
        (ctx, builder) -> {
            builder.suggest("3s");
            builder.suggest("5s");
            builder.suggest("10s");
            builder.suggest("30s");
            builder.suggest("1m");
            builder.suggest("5m");
            builder.suggest("60t");
            builder.suggest("120t");
            builder.suggest("200t");
            return builder.buildFuture();
        };

    /** Named colors + common hex values for tab-completion. */
    private static final SuggestionProvider<FabricClientCommandSource> COLOR_SUGGESTIONS =
        (ctx, builder) -> {
            // Named colors
            builder.suggest("red");
            builder.suggest("green");
            builder.suggest("blue");
            builder.suggest("gold");
            builder.suggest("yellow");
            builder.suggest("cyan");
            builder.suggest("magenta");
            builder.suggest("white");
            builder.suggest("orange");
            builder.suggest("purple");
            builder.suggest("pink");
            builder.suggest("lime");
            builder.suggest("aqua");
            builder.suggest("navy");
            builder.suggest("teal");
            builder.suggest("brown");
            builder.suggest("gray");
            builder.suggest("black");
            // Common hex
            builder.suggest("FF5555");
            builder.suggest("55FF55");
            builder.suggest("5555FF");
            builder.suggest("FFAA00");
            builder.suggest("FF55FF");
            builder.suggest("55FFFF");
            builder.suggest("FFFF55");
            builder.suggest("8BAAFF");
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
                    .then(literal("clear")
                        .executes(ctx -> clearSource(ctx.getSource()))
                    )
                )
                // /ccraft product <item>
                .then(literal("product")
                    .then(argument("item", StringArgumentType.greedyString())
                        .suggests(ITEM_SUGGESTIONS)
                        .executes(ctx -> setProduct(ctx.getSource(),
                            StringArgumentType.getString(ctx, "item")))
                    )
                    .then(literal("clear")
                        .executes(ctx -> clearProduct(ctx.getSource()))
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
                    .then(literal("clear")
                        .executes(ctx -> clearStation(ctx.getSource()))
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
                    .then(literal("clear")
                        .executes(ctx -> clearInput(ctx.getSource()))
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
                    .then(literal("clear")
                        .executes(ctx -> clearOutput(ctx.getSource()))
                    )
                )
                // /ccraft status
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource()))
                )
                // /ccraft show [duration] [station_color] [input_color] [output_color]
                .then(literal("show")
                    .executes(ctx -> showHighlight(ctx.getSource()))
                    .then(argument("duration", StringArgumentType.word())
                        .suggests(DURATION_SUGGESTIONS)
                        .executes(ctx -> showHighlight(ctx.getSource(),
                            StringArgumentType.getString(ctx, "duration")))
                        .then(argument("station_color", StringArgumentType.word())
                            .suggests(COLOR_SUGGESTIONS)
                            .executes(ctx -> showHighlight(ctx.getSource(),
                                StringArgumentType.getString(ctx, "duration"),
                                StringArgumentType.getString(ctx, "station_color")))
                            .then(argument("input_color", StringArgumentType.word())
                                .suggests(COLOR_SUGGESTIONS)
                                .executes(ctx -> showHighlight(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "duration"),
                                    StringArgumentType.getString(ctx, "station_color"),
                                    StringArgumentType.getString(ctx, "input_color")))
                                .then(argument("output_color", StringArgumentType.word())
                                    .suggests(COLOR_SUGGESTIONS)
                                    .executes(ctx -> showHighlight(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "duration"),
                                        StringArgumentType.getString(ctx, "station_color"),
                                        StringArgumentType.getString(ctx, "input_color"),
                                        StringArgumentType.getString(ctx, "output_color")))
                                )
                            )
                        )
                    )
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
                    .then(literal("clear")
                        .executes(ctx -> clearCount(ctx.getSource()))
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

    // ==================== Duration parsing ====================

    /**
     * Parses a duration string into ticks.
     * Supported formats:
     * <ul>
     *   <li>{@code "3s"} or {@code "3"} — seconds (×20)</li>
     *   <li>{@code "30t"} — raw ticks</li>
     *   <li>{@code "1m"} — minutes (×1200)</li>
     *   <li>{@code "500ms"} — milliseconds (/50, min 1 tick)</li>
     * </ul>
     *
     * @return ticks, or -1 if unparseable
     */
    private static int parseDuration(String input) {
        input = input.trim().toLowerCase();
        if (input.isEmpty()) return -1;

        try {
            if (input.endsWith("ms")) {
                int ms = Integer.parseInt(input.substring(0, input.length() - 2));
                return Math.max(1, ms / 50);
            }
            if (input.endsWith("s")) {
                return Integer.parseInt(input.substring(0, input.length() - 1)) * 20;
            }
            if (input.endsWith("t")) {
                return Integer.parseInt(input.substring(0, input.length() - 1));
            }
            if (input.endsWith("m")) {
                return Integer.parseInt(input.substring(0, input.length() - 1)) * 60 * 20;
            }
            // Plain number: treat as seconds
            return Integer.parseInt(input) * 20;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Convert duration ticks to a human-readable string for feedback. */
    private static String formatDuration(int ticks) {
        if (ticks % 1200 == 0) return (ticks / 1200) + "m";
        if (ticks % 20 == 0) return (ticks / 20) + "s";
        return ticks + "t";
    }

    // ==================== Color parsing ====================

    private static final Map<String, Integer> NAMED_COLORS = Map.ofEntries(
        Map.entry("red",     0xFF5555),
        Map.entry("green",   0x55FF55),
        Map.entry("blue",    0x5555FF),
        Map.entry("gold",    0xFFAA00),
        Map.entry("yellow",  0xFFFF55),
        Map.entry("cyan",    0x55FFFF),
        Map.entry("magenta", 0xFF55FF),
        Map.entry("white",   0xFFFFFF),
        Map.entry("black",   0x000000),
        Map.entry("gray",    0x888888),
        Map.entry("grey",    0x888888),
        Map.entry("orange",  0xFF8800),
        Map.entry("purple",  0xAA55FF),
        Map.entry("pink",    0xFF88AA),
        Map.entry("lime",    0x88FF00),
        Map.entry("aqua",    0x00FFFF),
        Map.entry("navy",    0x000080),
        Map.entry("teal",    0x008080),
        Map.entry("brown",   0x8B4513)
    );

    /**
     * Parses a color string into an RGB24 integer.
     * Supported formats:
     * <ul>
     *   <li>Named: {@code "red"}, {@code "green"}, {@code "blue"}, {@code "gold"}, etc.</li>
     *   <li>Hex: {@code "FF5555"}, {@code "0xFF5555"}, {@code "#FF5555"}</li>
     *   <li>Shorthand hex: {@code "F00"} → {@code FF0000}</li>
     * </ul>
     *
     * @return RGB24 color (0xRRGGBB), or -1 if unparseable
     */
    private static int parseColor(String input) {
        if (input == null || input.isEmpty()) return -1;

        String lower = input.trim().toLowerCase();

        // Named color
        if (NAMED_COLORS.containsKey(lower)) {
            return NAMED_COLORS.get(lower);
        }

        // Strip prefixes: #, 0x, 0X
        String hex = lower;
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }

        // Shorthand hex: "F00" → "FF0000"
        if (hex.length() == 3) {
            StringBuilder sb = new StringBuilder(6);
            for (char c : hex.toCharArray()) {
                sb.append(c).append(c);
            }
            hex = sb.toString();
        }

        if (hex.length() == 6) {
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {
            }
        }

        return -1;
    }

    /** Convert an RGB24 color to a #RRGGBB string for feedback. */
    private static String formatColor(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    // ==================== Individual subcommands ====================

    private static int setSource(FabricClientCommandSource source, String itemInput) {
        Item item = parseItem(itemInput);
        if (item == null) { source.sendFeedback(Component.translatable("client-tools.ccraft.unknown_item", itemInput)); return 0; }
        CcraftState.setSourceItem(item);
        source.sendFeedback(Component.translatable("client-tools.ccraft.source_set", BuiltInRegistries.ITEM.getKey(item).toString()));
        return 1;
    }

    private static int setProduct(FabricClientCommandSource source, String itemInput) {
        Item item = parseItem(itemInput);
        if (item == null) { source.sendFeedback(Component.translatable("client-tools.ccraft.unknown_item", itemInput)); return 0; }
        CcraftState.setProductItem(item);
        source.sendFeedback(Component.translatable("client-tools.ccraft.product_set", BuiltInRegistries.ITEM.getKey(item).toString()));
        return 1;
    }

    private static int setStationAimed(FabricClientCommandSource source) {
        BlockPos pos = getAimedBlockPos();
        if (pos == null) { source.sendFeedback(Component.translatable("client-tools.ccraft.not_looking_at_block")); return 0; }
        CcraftState.setStationPos(pos);
        source.sendFeedback(Component.translatable("client-tools.ccraft.station_set", pos.getX(), pos.getY(), pos.getZ()));
        return 1;
    }

    private static int setStationManual(FabricClientCommandSource source, int x, int y, int z) {
        CcraftState.setStationPos(new BlockPos(x, y, z));
        source.sendFeedback(Component.translatable("client-tools.ccraft.station_set", x, y, z));
        return 1;
    }

    private static int setInputAimed(FabricClientCommandSource source) {
        BlockPos pos = getAimedBlockPos();
        if (pos == null) { source.sendFeedback(Component.translatable("client-tools.ccraft.not_looking_at_block")); return 0; }
        CcraftState.setInputPos(pos);
        source.sendFeedback(Component.translatable("client-tools.ccraft.input_box_set", pos.getX(), pos.getY(), pos.getZ()));
        return 1;
    }

    private static int setInputManual(FabricClientCommandSource source, int x, int y, int z) {
        CcraftState.setInputPos(new BlockPos(x, y, z));
        source.sendFeedback(Component.translatable("client-tools.ccraft.input_box_set", x, y, z));
        return 1;
    }

    private static int setOutputAimed(FabricClientCommandSource source) {
        BlockPos pos = getAimedBlockPos();
        if (pos == null) { source.sendFeedback(Component.translatable("client-tools.ccraft.not_looking_at_block")); return 0; }
        CcraftState.setOutputPos(pos);
        source.sendFeedback(Component.translatable("client-tools.ccraft.output_box_set", pos.getX(), pos.getY(), pos.getZ()));
        return 1;
    }

    private static int setOutputManual(FabricClientCommandSource source, int x, int y, int z) {
        CcraftState.setOutputPos(new BlockPos(x, y, z));
        source.sendFeedback(Component.translatable("client-tools.ccraft.output_box_set", x, y, z));
        return 1;
    }

    private static int setCount(FabricClientCommandSource source, String value) {
        if (value.equalsIgnoreCase("infinite")) {
            CcraftState.setRepeatCount(-1);
            source.sendFeedback(Component.translatable("client-tools.ccraft.count_set_infinite"));
        } else {
            try {
                int count = Integer.parseInt(value);
                if (count < 1) {
                    source.sendFeedback(Component.translatable("client-tools.ccraft.count_must_be_positive"));
                    return 0;
                }
                CcraftState.setRepeatCount(count);
                source.sendFeedback(Component.translatable("client-tools.ccraft.count_set_finite", count));
            } catch (NumberFormatException e) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.count_invalid"));
                return 0;
            }
        }
        return 1;
    }

    // ==================== Individual clear subcommands ====================

    private static int clearSource(FabricClientCommandSource source) {
        CcraftState.clearSourceItem();
        source.sendFeedback(Component.translatable("client-tools.ccraft.source_cleared"));
        return 1;
    }

    private static int clearProduct(FabricClientCommandSource source) {
        CcraftState.clearProductItem();
        source.sendFeedback(Component.translatable("client-tools.ccraft.product_cleared"));
        return 1;
    }

    private static int clearStation(FabricClientCommandSource source) {
        CcraftState.clearStationPos();
        source.sendFeedback(Component.translatable("client-tools.ccraft.station_cleared"));
        return 1;
    }

    private static int clearInput(FabricClientCommandSource source) {
        CcraftState.clearInputPos();
        source.sendFeedback(Component.translatable("client-tools.ccraft.input_cleared"));
        return 1;
    }

    private static int clearOutput(FabricClientCommandSource source) {
        CcraftState.clearOutputPos();
        source.sendFeedback(Component.translatable("client-tools.ccraft.output_cleared"));
        return 1;
    }

    private static int clearCount(FabricClientCommandSource source) {
        CcraftState.clearRepeatCount();
        source.sendFeedback(Component.translatable("client-tools.ccraft.count_cleared"));
        return 1;
    }

    // ==================== Status / Clear ====================

    private static int showStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.ccraft.status_header"));

        // --- Runtime status ---
        CraftingExecutor executor = CraftingExecutor.getInstance();
        if (executor.isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_running"));
            int target = executor.getTargetCount();
            if (target > 0) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.status_progress_finite",
                    executor.getFinalProductsMade(), target,
                    executor.getCycleCount()));
            } else {
                source.sendFeedback(Component.translatable("client-tools.ccraft.status_progress_infinite",
                    executor.getFinalProductsMade(), executor.getCycleCount()));
            }
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_current_step",
                executor.getCurrentStepIndex() + 1, executor.getStepCount(), executor.getCurrentStepProgress()));
        } else if (executor.isDone()) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_done"));
            if (executor.getTotalCrafted() > 0) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.status_done_deposited",
                    executor.getTotalCrafted(), executor.getCycleCount()));
            } else if (executor.getFinalProductsMade() > 0) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.status_done_made",
                    executor.getFinalProductsMade(), executor.getCycleCount()));
            }
        } else if (executor.isError()) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_error",
                executor.getErrorMessage()));
        }

        // --- Settings ---
        source.sendFeedback(Component.translatable("client-tools.ccraft.status_settings"));
        printParam(source, "Source", CcraftState.getSourceItem());
        printParam(source, "Product", CcraftState.getProductItem());
        printPos(source, "Station", CcraftState.getStationPos());
        printPos(source, "Input box", CcraftState.getInputPos());
        printPos(source, "Output box", CcraftState.getOutputPos());
        int count = CcraftState.getRepeatCount();
        if (count == -1) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_target_infinite"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_target_finite", count));
        }
        if (executor.isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_stop_hint"));
        } else if (CcraftState.isReady()) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_ready"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ccraft.status_missing", CcraftState.getMissingParams()));
        }
        return 1;
    }

    private static void printParam(FabricClientCommandSource source, String label, Item item) {
        if (item != null) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.param_set", label, BuiltInRegistries.ITEM.getKey(item).toString()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ccraft.param_not_set", label));
        }
    }

    private static void printPos(FabricClientCommandSource source, String label, BlockPos pos) {
        if (pos != null) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.param_set", label, pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ccraft.param_not_set", label));
        }
    }

    // ==================== Show (highlight) ====================

    /** Show with all defaults: 3s, default colors. */
    private static int showHighlight(FabricClientCommandSource source) {
        return doShowHighlight(source,
            CcraftHighlightRenderer.DEFAULT_DURATION_TICKS,
            CcraftHighlightRenderer.DEFAULT_STATION_COLOR,
            CcraftHighlightRenderer.DEFAULT_INPUT_COLOR,
            CcraftHighlightRenderer.DEFAULT_OUTPUT_COLOR);
    }

    /** Show with custom duration, default colors. */
    private static int showHighlight(FabricClientCommandSource source, String durationStr) {
        int ticks = parseDuration(durationStr);
        if (ticks < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_duration_invalid", durationStr));
            return 0;
        }
        return doShowHighlight(source, ticks,
            CcraftHighlightRenderer.DEFAULT_STATION_COLOR,
            CcraftHighlightRenderer.DEFAULT_INPUT_COLOR,
            CcraftHighlightRenderer.DEFAULT_OUTPUT_COLOR);
    }

    /** Show with custom duration + station color, default input/output colors. */
    private static int showHighlight(FabricClientCommandSource source, String durationStr, String stationColorStr) {
        int ticks = parseDuration(durationStr);
        if (ticks < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_duration_invalid", durationStr));
            return 0;
        }
        int stationColor = parseColor(stationColorStr);
        if (stationColor < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_color_invalid", stationColorStr));
            return 0;
        }
        return doShowHighlight(source, ticks, stationColor,
            CcraftHighlightRenderer.DEFAULT_INPUT_COLOR,
            CcraftHighlightRenderer.DEFAULT_OUTPUT_COLOR);
    }

    /** Show with custom duration + station + input colors, default output color. */
    private static int showHighlight(FabricClientCommandSource source, String durationStr,
                                      String stationColorStr, String inputColorStr) {
        int ticks = parseDuration(durationStr);
        if (ticks < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_duration_invalid", durationStr));
            return 0;
        }
        int stationColor = parseColor(stationColorStr);
        if (stationColor < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_color_invalid", stationColorStr));
            return 0;
        }
        int inputColor = parseColor(inputColorStr);
        if (inputColor < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_color_invalid", inputColorStr));
            return 0;
        }
        return doShowHighlight(source, ticks, stationColor, inputColor,
            CcraftHighlightRenderer.DEFAULT_OUTPUT_COLOR);
    }

    /** Show with custom duration + all three colors. */
    private static int showHighlight(FabricClientCommandSource source, String durationStr,
                                      String stationColorStr, String inputColorStr, String outputColorStr) {
        int ticks = parseDuration(durationStr);
        if (ticks < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_duration_invalid", durationStr));
            return 0;
        }
        int stationColor = parseColor(stationColorStr);
        if (stationColor < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_color_invalid", stationColorStr));
            return 0;
        }
        int inputColor = parseColor(inputColorStr);
        if (inputColor < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_color_invalid", inputColorStr));
            return 0;
        }
        int outputColor = parseColor(outputColorStr);
        if (outputColor < 0) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_color_invalid", outputColorStr));
            return 0;
        }
        return doShowHighlight(source, ticks, stationColor, inputColor, outputColor);
    }

    /** Core implementation: triggers highlight and sends feedback. */
    private static int doShowHighlight(FabricClientCommandSource source, int ticks,
                                        int stationColor, int inputColor, int outputColor) {
        if (CcraftState.getInputPos() == null && CcraftState.getOutputPos() == null) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.show_no_positions"));
            return 0;
        }
        CcraftHighlightRenderer.trigger(ticks, stationColor, inputColor, outputColor);
        String durStr = formatDuration(ticks);
        String sCol = formatColor(stationColor);
        String iCol = formatColor(inputColor);
        String oCol = formatColor(outputColor);
        source.sendFeedback(Component.translatable("client-tools.ccraft.show_triggered_custom",
            durStr, sCol, iCol, oCol));
        return 1;
    }

    // ==================== Clear all ====================

    private static int clearState(FabricClientCommandSource source) {
        CcraftState.clear();
        source.sendFeedback(Component.translatable("client-tools.ccraft.state_cleared"));
        return 1;
    }

    // ==================== Run ====================

    private static int runCraft(FabricClientCommandSource source) {
        if (!CcraftState.isReady()) {
            source.sendFeedback(Component.translatable("client-tools.ccraft.missing_params", CcraftState.getMissingParams()));
            return 0;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) { source.sendFeedback(Component.translatable("client-tools.ccraft.not_in_world")); return 0; }

        // Auto-find crafting table if not set
        BlockPos station = CcraftState.getStationPos();
        if (station == null) {
            station = CraftingExecutor.findNearestCraftingTable(client, CcraftState.getInputPos(), 16);
            if (station == null) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.no_table_found"));
                return 0;
            }
            // Persist auto-detected station so highlight can show it
            CcraftState.setStationPos(station);
            source.sendFeedback(Component.translatable("client-tools.ccraft.auto_detected_table",
                station.getX(), station.getY(), station.getZ()));
        }

        Item productItem = CcraftState.getProductItem();
        BlockPos input = CcraftState.getInputPos();
        BlockPos output = CcraftState.getOutputPos();
        RecipeManager rm = client.level.getRecipeManager();
        HolderLookup.Provider reg = client.level.registryAccess();
        int repeatCount = CcraftState.getRepeatCount();
        String countLabel = repeatCount == -1 ? "infinite" : String.valueOf(repeatCount);

        if (CcraftState.hasSourceItem()) {
            // --- Legacy mode: fixed source → product chain ---
            Item sourceItem = CcraftState.getSourceItem();
            RecipeChainAnalyzer.RecipeChain chain = RecipeChainAnalyzer.analyze(sourceItem, productItem, rm, reg);
            if (chain == null) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.no_chain_found",
                    BuiltInRegistries.ITEM.getKey(sourceItem).toString(),
                    BuiltInRegistries.ITEM.getKey(productItem).toString()));
                return 0;
            }
            if (chain.steps().isEmpty()) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.source_equals_product"));
                return 0;
            }

            source.sendFeedback(Component.translatable("client-tools.ccraft.chain_header"));
            for (int i = 0; i < chain.steps().size(); i++) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.chain_step", i + 1, chain.steps().get(i).toString()));
            }
            source.sendFeedback(Component.translatable("client-tools.ccraft.chain_footer"));
            source.sendFeedback(Component.translatable("client-tools.ccraft.location_info",
                station.getX(), station.getY(), station.getZ(),
                input.getX(), input.getY(), input.getZ(),
                output.getX(), output.getY(), output.getZ()));

            if (client.player != null && client.player.blockPosition().distSqr(station) > 36) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.move_closer"));
            }

            CraftingExecutor.getInstance().start(chain.steps(), station, input, output, productItem, repeatCount);
            source.sendFeedback(Component.translatable("client-tools.ccraft.executing", countLabel));
        } else {
            // --- Auto-detect mode: scan input chest, build multi-source plan ---
            MaterialPlanner planner = new MaterialPlanner(rm, reg);

            source.sendFeedback(Component.translatable("client-tools.ccraft.auto_mode",
                BuiltInRegistries.ITEM.getKey(productItem).toString()));
            source.sendFeedback(Component.translatable("client-tools.ccraft.auto_scanning"));
            source.sendFeedback(Component.translatable("client-tools.ccraft.location_info",
                station.getX(), station.getY(), station.getZ(),
                input.getX(), input.getY(), input.getZ(),
                output.getX(), output.getY(), output.getZ()));

            if (client.player != null && client.player.blockPosition().distSqr(station) > 36) {
                source.sendFeedback(Component.translatable("client-tools.ccraft.move_closer"));
            }

            CraftingExecutor.getInstance().startAuto(productItem, repeatCount, station, input, output, planner);
            source.sendFeedback(Component.translatable("client-tools.ccraft.executing", countLabel));
        }
        // Show block highlight so the player can visually confirm the positions
        CcraftHighlightRenderer.trigger();
        return 1;
    }

    // ==================== Stop ====================

    private static int stopCraft(FabricClientCommandSource source) {
        if (CraftingExecutor.getInstance().isRunning()) {
            CraftingExecutor.getInstance().stop();
            source.sendFeedback(Component.translatable("client-tools.ccraft.stopped"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ccraft.not_running"));
        }
        return 1;
    }
}
