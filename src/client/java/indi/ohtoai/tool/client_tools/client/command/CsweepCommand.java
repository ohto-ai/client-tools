package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.SweepExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.SweepState;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Registers the {@code /csweep} command for automated snake-pattern
 * area traversal (movement only — mining is handled by other mods).
 *
 * <pre>
 * /csweep
 *   pos1 [x y z]
 *   pos2 [x y z]
 *   radius &lt;1..64&gt;
 *   speed &lt;0.5..100&gt;
 *   show              — show current display settings
 *   show outline      — toggle cuboid outline
 *   show path         — toggle path lines
 *   start             — begin sweep
 *   stop              — halt sweep
 *   status            — show config + progress
 *   reset             — clear all settings
 * </pre>
 */
public class CsweepCommand {

    private static BlockPos getAimedBlockPos() {
        Minecraft client = Minecraft.getInstance();
        if (client.hitResult instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private static final SuggestionProvider<FabricClientCommandSource> COORD_X = (ctx, suggestions) -> {
        BlockPos p = getAimedBlockPos();
        if (p != null) suggestions.suggest(String.valueOf(p.getX()));
        return suggestions.buildFuture();
    };
    private static final SuggestionProvider<FabricClientCommandSource> COORD_Y = (ctx, suggestions) -> {
        BlockPos p = getAimedBlockPos();
        if (p != null) suggestions.suggest(String.valueOf(p.getY()));
        return suggestions.buildFuture();
    };
    private static final SuggestionProvider<FabricClientCommandSource> COORD_Z = (ctx, suggestions) -> {
        BlockPos p = getAimedBlockPos();
        if (p != null) suggestions.suggest(String.valueOf(p.getZ()));
        return suggestions.buildFuture();
    };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("csweep")
                // ---- pos1 ----
                .then(literal("pos1")
                    .executes(ctx -> setPos1Aimed(ctx.getSource()))
                    .then(argument("x", IntegerArgumentType.integer())
                        .suggests(COORD_X)
                        .then(argument("y", IntegerArgumentType.integer())
                            .suggests(COORD_Y)
                            .then(argument("z", IntegerArgumentType.integer())
                                .suggests(COORD_Z)
                                .executes(ctx -> setPos1(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"),
                                    IntegerArgumentType.getInteger(ctx, "z")))))))
                // ---- pos2 ----
                .then(literal("pos2")
                    .executes(ctx -> setPos2Aimed(ctx.getSource()))
                    .then(argument("x", IntegerArgumentType.integer())
                        .suggests(COORD_X)
                        .then(argument("y", IntegerArgumentType.integer())
                            .suggests(COORD_Y)
                            .then(argument("z", IntegerArgumentType.integer())
                                .suggests(COORD_Z)
                                .executes(ctx -> setPos2(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"),
                                    IntegerArgumentType.getInteger(ctx, "z")))))))
                // ---- radius ----
                .then(literal("radius")
                    .then(argument("blocks", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> setRadius(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "blocks"))))
                    .executes(ctx -> showRadius(ctx.getSource())))
                // ---- speed ----
                .then(literal("speed")
                    .then(argument("bpt", DoubleArgumentType.doubleArg(0.5, 100.0))
                        .executes(ctx -> setSpeed(ctx.getSource(),
                            DoubleArgumentType.getDouble(ctx, "bpt"))))
                    .executes(ctx -> showSpeed(ctx.getSource())))
                // ---- show ----
                .then(literal("show")
                    .executes(ctx -> showDisplayStatus(ctx.getSource()))
                    .then(literal("outline")
                        .executes(ctx -> toggleOutline(ctx.getSource())))
                    .then(literal("path")
                        .executes(ctx -> togglePath(ctx.getSource()))))
                // ---- start ----
                .then(literal("start")
                    .executes(ctx -> startSweep(ctx.getSource())))
                // ---- stop ----
                .then(literal("stop")
                    .executes(ctx -> stopSweep(ctx.getSource())))
                // ---- pause (toggle) ----
                .then(literal("pause")
                    .executes(ctx -> togglePause(ctx.getSource())))
                // ---- status ----
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
                // ---- reset ----
                .then(literal("reset")
                    .executes(ctx -> resetSweep(ctx.getSource())))
        );
    }

    // ==================== pos1 / pos2 ====================

    private static int setPos1Aimed(FabricClientCommandSource source) {
        BlockPos pos = getAimedBlockPos();
        if (pos == null) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                pos = client.player.blockPosition();
            } else {
                source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
                return 0;
            }
        }
        return setPos1(source, pos.getX(), pos.getY(), pos.getZ());
    }

    private static int setPos1(FabricClientCommandSource source, int x, int y, int z) {
        SweepState.setPos1(new BlockPos(x, y, z));
        source.sendFeedback(Component.translatable("client-tools.csweep.pos1_set", x, y, z));
        // Auto-enable outline when any position is set (even without the other)
        if (!SweepState.isShowOutline()) {
            SweepState.setShowOutline(true);
            source.sendFeedback(Component.translatable("client-tools.csweep.show_outline_on"));
        }
        return 1;
    }

    private static int setPos2Aimed(FabricClientCommandSource source) {
        BlockPos pos = getAimedBlockPos();
        if (pos == null) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                pos = client.player.blockPosition();
            } else {
                source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
                return 0;
            }
        }
        return setPos2(source, pos.getX(), pos.getY(), pos.getZ());
    }

    private static int setPos2(FabricClientCommandSource source, int x, int y, int z) {
        SweepState.setPos2(new BlockPos(x, y, z));
        source.sendFeedback(Component.translatable("client-tools.csweep.pos2_set", x, y, z));
        if (!SweepState.isShowOutline()) {
            SweepState.setShowOutline(true);
            source.sendFeedback(Component.translatable("client-tools.csweep.show_outline_on"));
        }
        return 1;
    }

    // ==================== radius / speed ====================

    private static int setRadius(FabricClientCommandSource source, int blocks) {
        SweepState.setRadius(blocks);
        source.sendFeedback(Component.translatable("client-tools.csweep.radius_set", blocks));
        return 1;
    }

    private static int showRadius(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.csweep.radius_show", SweepState.getRadius()));
        return 1;
    }

    private static int setSpeed(FabricClientCommandSource source, double bpt) {
        SweepState.setSpeed(bpt);
        source.sendFeedback(Component.translatable("client-tools.csweep.speed_set", bpt));
        return 1;
    }

    private static int showSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.csweep.speed_show", SweepState.getSpeed()));
        return 1;
    }

    // ==================== show ====================

    private static int toggleOutline(FabricClientCommandSource source) {
        boolean enabled = !SweepState.isShowOutline();
        SweepState.setShowOutline(enabled);
        source.sendFeedback(Component.translatable(
            enabled ? "client-tools.csweep.show_outline_on" : "client-tools.csweep.show_outline_off"));
        return 1;
    }

    private static int togglePath(FabricClientCommandSource source) {
        boolean enabled = !SweepState.isShowPath();
        SweepState.setShowPath(enabled);
        source.sendFeedback(Component.translatable(
            enabled ? "client-tools.csweep.show_path_on" : "client-tools.csweep.show_path_off"));
        return 1;
    }

    private static int showDisplayStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.csweep.show_status",
            SweepState.isShowOutline() ? "§aON" : "§7OFF",
            SweepState.isShowPath() ? "§aON" : "§7OFF"));
        return 1;
    }

    // ==================== start / stop / pause ====================

    private static int startSweep(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
            return 0;
        }

        // If there's a saved paused state, resume instead
        if (SweepState.isPaused()) {
            return resumeSweep(source);
        }

        if (!SweepState.hasPositions()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.pos_not_set"));
            return 0;
        }
        if (!client.player.getAbilities().flying) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_flying"));
            return 0;
        }
        if (CraftingExecutor.getInstance().isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.crafting_running"));
            return 0;
        }
        if (SweepExecutor.getInstance().isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.already_running"));
            return 0;
        }

        // Auto-enable highlight during operation
        if (!SweepState.isShowOutline()) SweepState.setShowOutline(true);
        if (!SweepState.isShowPath()) SweepState.setShowPath(true);

        SweepExecutor.getInstance().start();
        long volume = SweepState.getVolume();
        String volumeStr = volume >= 1_000_000 ? String.format("%.1fM", volume / 1_000_000.0)
            : volume >= 1_000 ? String.format("%.1fK", volume / 1_000.0)
            : String.valueOf(volume);
        // Total stations are computed during BUILD_PATH, estimate now
        double spacing = Math.max(1.0, SweepState.getRadius() * 1.5);
        int dx = SweepState.getMaxX() - SweepState.getMinX();
        int dy = SweepState.getMaxY() - SweepState.getMinY();
        int dz = SweepState.getMaxZ() - SweepState.getMinZ();
        int estStations = ((int) Math.ceil(dx / spacing) + 1)
                        * ((int) Math.ceil(dy / spacing) + 1)
                        * ((int) Math.ceil(dz / spacing) + 1);
        source.sendFeedback(Component.translatable("client-tools.csweep.start", estStations, volumeStr));
        return 1;
    }

    private static int stopSweep(FabricClientCommandSource source) {
        SweepExecutor executor = SweepExecutor.getInstance();
        if (executor.isRunning() || executor.isPaused()) {
            executor.stop();
            source.sendFeedback(Component.translatable("client-tools.csweep.stopped"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_running"));
        }
        return 1;
    }

    private static int togglePause(FabricClientCommandSource source) {
        SweepExecutor executor = SweepExecutor.getInstance();
        if (executor.isRunning()) {
            executor.pause();
            source.sendFeedback(Component.translatable("client-tools.csweep.paused",
                executor.getCurrentStationIndex() + 1, executor.getTotalStations()));
        } else if (executor.isPaused()) {
            return resumeSweep(source);
        } else if (SweepState.isPaused()) {
            return resumeSweep(source);
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_running"));
        }
        return 1;
    }

    private static int resumeSweep(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
            return 0;
        }
        if (!client.player.getAbilities().flying) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_flying"));
            return 0;
        }
        SweepExecutor executor = SweepExecutor.getInstance();
        if (executor.resume()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.resumed",
                executor.getCurrentStationIndex() + 1, executor.getTotalStations()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.resume_failed"));
        }
        return 1;
    }

    // ==================== status ====================

    private static int showStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.csweep.status_header"));

        BlockPos p1 = SweepState.getPos1();
        if (p1 != null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_pos1", p1.getX(), p1.getY(), p1.getZ()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.param_not_set", "Pos1"));
        }

        BlockPos p2 = SweepState.getPos2();
        if (p2 != null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_pos2", p2.getX(), p2.getY(), p2.getZ()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.param_not_set", "Pos2"));
        }

        source.sendFeedback(Component.translatable("client-tools.csweep.status_radius", SweepState.getRadius()));
        source.sendFeedback(Component.translatable("client-tools.csweep.status_speed", SweepState.getSpeed()));

        source.sendFeedback(Component.translatable("client-tools.csweep.status_show",
            SweepState.isShowOutline() ? "§aON" : "§7OFF",
            SweepState.isShowPath() ? "§aON" : "§7OFF"));

        if (SweepState.hasPositions()) {
            long volume = SweepState.getVolume();
            String volumeStr = volume >= 1_000_000 ? String.format("%.1fM", volume / 1_000_000.0)
                : volume >= 1_000 ? String.format("%.1fK", volume / 1_000.0)
                : String.valueOf(volume);
            source.sendFeedback(Component.translatable("client-tools.csweep.status_volume", volumeStr));
        }

        SweepExecutor executor = SweepExecutor.getInstance();
        if (executor.isPaused()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_paused",
                executor.getCurrentStationIndex() + 1, executor.getTotalStations()));
        } else if (SweepState.isPaused()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_paused_saved",
                SweepState.getSavedStationIndex() + 1));
        } else if (executor.isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_running",
                executor.getCurrentStationIndex() + 1, executor.getTotalStations()));
        } else if (executor.isDone()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_done", executor.getTotalStations()));
        } else if (executor.isError()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_error", executor.getErrorMessage()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_idle"));
        }

        return 1;
    }

    // ==================== reset ====================

    private static int resetSweep(FabricClientCommandSource source) {
        SweepExecutor executor = SweepExecutor.getInstance();
        if (executor.isRunning()) executor.stop();
        SweepState.clear();
        source.sendFeedback(Component.translatable("client-tools.csweep.reset"));
        return 1;
    }
}
