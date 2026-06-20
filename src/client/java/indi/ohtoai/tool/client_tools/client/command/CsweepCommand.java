package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.LitematicaIntegration;
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
 *   show layer        — toggle layer emphasis (dim non-current, thick current)
 *   show dir          — toggle nearest station direction line
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

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"pos1", "pos2", "radius", "speed", "maxspeed",
                "show", "litematica", "nearest", "autospeed", "avoidwater", "blockdetect",
                "next", "expand", "contract", "start", "stop", "pause", "status", "penalty", "reset"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("csweep")
                .executes(ctx -> showBriefHelp(ctx.getSource()))
                // /csweep help [subcommand]
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand")))))
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
                // ---- maxspeed ----
                .then(literal("maxspeed")
                    .then(argument("bpt", DoubleArgumentType.doubleArg(0.5, 100.0))
                        .executes(ctx -> setMaxSpeed(ctx.getSource(),
                            DoubleArgumentType.getDouble(ctx, "bpt"))))
                    .executes(ctx -> showMaxSpeed(ctx.getSource())))
                // ---- show ----
                .then(literal("show")
                    .executes(ctx -> showDisplayStatus(ctx.getSource()))
                    .then(literal("outline")
                        .executes(ctx -> toggleOutline(ctx.getSource()))
                        .then(literal("on")
                            .executes(ctx -> setOutline(ctx.getSource(), true)))
                        .then(literal("off")
                            .executes(ctx -> setOutline(ctx.getSource(), false))))
                    .then(literal("path")
                        .executes(ctx -> togglePath(ctx.getSource()))
                        .then(literal("on")
                            .executes(ctx -> setPath(ctx.getSource(), true)))
                        .then(literal("off")
                            .executes(ctx -> setPath(ctx.getSource(), false))))
                    .then(literal("layer")
                        .executes(ctx -> toggleLayer(ctx.getSource()))
                        .then(literal("on")
                            .executes(ctx -> setLayer(ctx.getSource(), true)))
                        .then(literal("off")
                            .executes(ctx -> setLayer(ctx.getSource(), false))))
                    .then(literal("dir")
                        .executes(ctx -> toggleNearestDirection(ctx.getSource()))
                        .then(literal("on")
                            .executes(ctx -> setNearestDirection(ctx.getSource(), true)))
                        .then(literal("off")
                            .executes(ctx -> setNearestDirection(ctx.getSource(), false)))))
                // ---- litematica ----
                .then(literal("litematica")
                    .then(literal("on")
                        .executes(ctx -> setLitematicaSync(ctx.getSource(), true)))
                    .then(literal("off")
                        .executes(ctx -> setLitematicaSync(ctx.getSource(), false)))
                    .then(literal("sync")
                        .executes(ctx -> forceSyncLitematica(ctx.getSource())))
                    .executes(ctx -> showLitematicaStatus(ctx.getSource())))
                // ---- nearest (toggle real-time nearest station tracking) ----
                .then(literal("nearest")
                    .executes(ctx -> toggleNearestTracking(ctx.getSource()))
                    .then(literal("on")
                        .executes(ctx -> setNearestTracking(ctx.getSource(), true)))
                    .then(literal("off")
                        .executes(ctx -> setNearestTracking(ctx.getSource(), false))))
                // ---- autospeed (adaptive speed based on block density) ----
                .then(literal("autospeed")
                    .executes(ctx -> toggleAutoSpeed(ctx.getSource()))
                    .then(literal("on")
                        .executes(ctx -> setAutoSpeed(ctx.getSource(), true)))
                    .then(literal("off")
                        .executes(ctx -> setAutoSpeed(ctx.getSource(), false))))
                // ---- avoidwater (toggle Y adjustment to stay out of water) ----
                .then(literal("avoidwater")
                    .executes(ctx -> toggleAvoidWater(ctx.getSource()))
                    .then(literal("on")
                        .executes(ctx -> setAvoidWater(ctx.getSource(), true)))
                    .then(literal("off")
                        .executes(ctx -> setAvoidWater(ctx.getSource(), false))))
                // ---- blockdetect (backward hemisphere blockage detection) ----
                .then(literal("blockdetect")
                    .executes(ctx -> toggleBlockageDetection(ctx.getSource()))
                    .then(literal("on")
                        .executes(ctx -> setBlockageDetection(ctx.getSource(), true)))
                    .then(literal("off")
                        .executes(ctx -> setBlockageDetection(ctx.getSource(), false)))
                    .then(literal("wait")
                        .executes(ctx -> setBlockageStop(ctx.getSource(), true)))
                    .then(literal("slow")
                        .executes(ctx -> setBlockageStop(ctx.getSource(), false))))
                // ---- next (skip to next sub-region) ----
                .then(literal("next")
                    .executes(ctx -> skipToNextRegion(ctx.getSource())))
                // ---- expand ----
                .then(literal("expand")
                    .then(argument("blocks", IntegerArgumentType.integer(1))
                        .executes(ctx -> expandCuboid(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "blocks")))))
                // ---- contract ----
                .then(literal("contract")
                    .then(argument("blocks", IntegerArgumentType.integer(1))
                        .executes(ctx -> contractCuboid(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "blocks")))))
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
                // ---- penalty (debug mining penalty / onGround state) ----
                .then(literal("penalty")
                    .executes(ctx -> showPenalty(ctx.getSource())))
                // ---- reset ----
                .then(literal("reset")
                    .executes(ctx -> resetSweep(ctx.getSource())))
        );
    }

    // ==================== Help ====================

    private static int showBriefHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.csweep.help.brief"));
        return 1;
    }

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.csweep.help.header"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.pos"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.radius"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.speed"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.maxspeed"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.autospeed"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.avoidwater"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.blockdetect"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.show"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.litematica"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.nearest"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.expand_contract"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.next"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.control"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.penalty"));
        source.sendFeedback(Component.translatable("client-tools.csweep.help.quick_start"));
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "pos1", "pos2" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.pos_detail"));
            }
            case "radius" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.radius_detail"));
            }
            case "speed" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.speed_detail"));
            }
            case "maxspeed" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.maxspeed_detail"));
            }
            case "autospeed" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.autospeed_detail"));
            }
            case "avoidwater" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.avoidwater_detail"));
            }
            case "blockdetect" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.blockdetect_detail"));
            }
            case "show" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.show_detail"));
            }
            case "litematica" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.litematica_detail"));
            }
            case "nearest" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.nearest_detail"));
            }
            case "expand", "contract" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.expand_contract_detail"));
            }
            case "next" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.next_detail"));
            }
            case "start" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.start_detail"));
            }
            case "stop" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.stop_detail"));
            }
            case "pause" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.pause_detail"));
            }
            case "status" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.status_detail"));
            }
            case "penalty" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.penalty_detail"));
            }
            case "reset" -> {
                source.sendFeedback(Component.translatable("client-tools.csweep.help.reset_detail"));
            }
            default -> source.sendFeedback(Component.translatable("client-tools.csweep.help.unknown_subcommand", subcommand));
        }
        return 1;
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
        if (SweepState.isSyncLitematica()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.litematica.auto_disabled"));
        }
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
        if (SweepState.isSyncLitematica()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.litematica.auto_disabled"));
        }
        SweepState.setPos2(new BlockPos(x, y, z));
        source.sendFeedback(Component.translatable("client-tools.csweep.pos2_set", x, y, z));
        if (!SweepState.isShowOutline()) {
            SweepState.setShowOutline(true);
            source.sendFeedback(Component.translatable("client-tools.csweep.show_outline_on"));
        }
        return 1;
    }

    // ==================== expand / shrink ====================

    /**
     * Determines which face of the cuboid the player is facing and adjusts
     * that bound outward (expand) or inward (shrink) by the given amount.
     */
    private static int adjustCuboid(FabricClientCommandSource source, int blocks, boolean expand) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
            return 0;
        }
        if (!SweepState.hasPositions()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.pos_not_set"));
            return 0;
        }

        BlockPos p1 = SweepState.getPos1();
        BlockPos p2 = SweepState.getPos2();

        // Get player's look direction
        var look = client.player.getLookAngle();
        double ax = Math.abs(look.x), ay = Math.abs(look.y), az = Math.abs(look.z);

        String action = expand ? "expand" : "contract";
        int newVal;
        BlockPos newP1, newP2;

        // Detemine which face: dominant axis + sign
        if (ay >= ax && ay >= az) {
            // Vertical — looking up or down
            if (look.y > 0) {
                // Looking up — expand maxY / shrink maxY
                int maxY = Math.max(p1.getY(), p2.getY());
                newVal = expand ? maxY + blocks : maxY - blocks;
                if (!expand && newVal < Math.min(p1.getY(), p2.getY())) newVal = Math.min(p1.getY(), p2.getY());
                newP1 = p1.getY() == maxY ? new BlockPos(p1.getX(), newVal, p1.getZ()) : p1;
                newP2 = p2.getY() == maxY ? new BlockPos(p2.getX(), newVal, p2.getZ()) : p2;
                SweepState.setPos1(newP1);
                SweepState.setPos2(newP2);
            } else {
                // Looking down — expand minY / shrink minY
                int minY = Math.min(p1.getY(), p2.getY());
                newVal = expand ? minY - blocks : minY + blocks;
                if (!expand && newVal > Math.max(p1.getY(), p2.getY())) newVal = Math.max(p1.getY(), p2.getY());
                newP1 = p1.getY() == minY ? new BlockPos(p1.getX(), newVal, p1.getZ()) : p1;
                newP2 = p2.getY() == minY ? new BlockPos(p2.getX(), newVal, p2.getZ()) : p2;
                SweepState.setPos1(newP1);
                SweepState.setPos2(newP2);
            }
            source.sendFeedback(Component.translatable("client-tools.csweep." + action, blocks, "Y"));
        } else if (ax >= az) {
            // Horizontal — X dominant
            if (look.x > 0) {
                // Looking +X — expand maxX
                int maxX = Math.max(p1.getX(), p2.getX());
                newVal = expand ? maxX + blocks : maxX - blocks;
                if (!expand && newVal < Math.min(p1.getX(), p2.getX())) newVal = Math.min(p1.getX(), p2.getX());
                newP1 = p1.getX() == maxX ? new BlockPos(newVal, p1.getY(), p1.getZ()) : p1;
                newP2 = p2.getX() == maxX ? new BlockPos(newVal, p2.getY(), p2.getZ()) : p2;
                SweepState.setPos1(newP1);
                SweepState.setPos2(newP2);
            } else {
                // Looking -X — expand minX
                int minX = Math.min(p1.getX(), p2.getX());
                newVal = expand ? minX - blocks : minX + blocks;
                if (!expand && newVal > Math.max(p1.getX(), p2.getX())) newVal = Math.max(p1.getX(), p2.getX());
                newP1 = p1.getX() == minX ? new BlockPos(newVal, p1.getY(), p1.getZ()) : p1;
                newP2 = p2.getX() == minX ? new BlockPos(newVal, p2.getY(), p2.getZ()) : p2;
                SweepState.setPos1(newP1);
                SweepState.setPos2(newP2);
            }
            source.sendFeedback(Component.translatable("client-tools.csweep." + action, blocks, "X"));
        } else {
            // Horizontal — Z dominant
            if (look.z > 0) {
                // Looking +Z — expand maxZ
                int maxZ = Math.max(p1.getZ(), p2.getZ());
                newVal = expand ? maxZ + blocks : maxZ - blocks;
                if (!expand && newVal < Math.min(p1.getZ(), p2.getZ())) newVal = Math.min(p1.getZ(), p2.getZ());
                newP1 = p1.getZ() == maxZ ? new BlockPos(p1.getX(), p1.getY(), newVal) : p1;
                newP2 = p2.getZ() == maxZ ? new BlockPos(p2.getX(), p2.getY(), newVal) : p2;
                SweepState.setPos1(newP1);
                SweepState.setPos2(newP2);
            } else {
                // Looking -Z — expand minZ
                int minZ = Math.min(p1.getZ(), p2.getZ());
                newVal = expand ? minZ - blocks : minZ + blocks;
                if (!expand && newVal > Math.max(p1.getZ(), p2.getZ())) newVal = Math.max(p1.getZ(), p2.getZ());
                newP1 = p1.getZ() == minZ ? new BlockPos(p1.getX(), p1.getY(), newVal) : p1;
                newP2 = p2.getZ() == minZ ? new BlockPos(p2.getX(), p2.getY(), newVal) : p2;
                SweepState.setPos1(newP1);
                SweepState.setPos2(newP2);
            }
            source.sendFeedback(Component.translatable("client-tools.csweep." + action, blocks, "Z"));
        }

        return 1;
    }

    private static int expandCuboid(FabricClientCommandSource source, int blocks) {
        return adjustCuboid(source, blocks, true);
    }

    private static int contractCuboid(FabricClientCommandSource source, int blocks) {
        return adjustCuboid(source, blocks, false);
    }

    // ==================== litematica sync ====================

    private static int setLitematicaSync(FabricClientCommandSource source, boolean enabled) {
        SweepState.setSyncLitematica(enabled);
        if (enabled) {
            if (LitematicaIntegration.isAvailable()) {
                var regions = LitematicaIntegration.getSubRegions();
                if (regions.isEmpty()) {
                    source.sendFeedback(Component.translatable(
                        "client-tools.csweep.litematica.sync_on_no_selection"));
                } else {
                    source.sendFeedback(Component.translatable(
                        "client-tools.csweep.litematica.sync_on", regions.size()));
                }
            } else {
                source.sendFeedback(Component.translatable(
                    "client-tools.csweep.litematica.not_available"));
            }
        } else {
            source.sendFeedback(Component.translatable(
                "client-tools.csweep.litematica.sync_off"));
        }
        return 1;
    }

    private static int forceSyncLitematica(FabricClientCommandSource source) {
        SweepExecutor executor = SweepExecutor.getInstance();
        if (executor.isRunning() || executor.isPaused()) {
            executor.stop();
        }
        SweepState.setSyncLitematica(true);
        SweepState.refreshSubRegions();
        return setLitematicaSync(source, true);
    }

    private static int showLitematicaStatus(FabricClientCommandSource source) {
        boolean available = LitematicaIntegration.isAvailable();
        boolean syncOn = SweepState.isSyncLitematica();
        source.sendFeedback(Component.translatable("client-tools.csweep.litematica.status_header"));
        source.sendFeedback(Component.translatable(
            "client-tools.csweep.litematica.status_sync", syncOn ? "§aON" : "§7OFF"));
        source.sendFeedback(Component.translatable(
            "client-tools.csweep.litematica.status_available", available ? "§aYES" : "§cNO"));

        if (!available) {
            String err = LitematicaIntegration.getInitError();
            if (err != null) {
                source.sendFeedback(Component.literal("§cError: §7" + err));
            }
        }

        if (available && syncOn) {
            var regions = LitematicaIntegration.getSubRegions();
            source.sendFeedback(Component.translatable(
                "client-tools.csweep.litematica.status_regions", regions.size()));
            for (int i = 0; i < regions.size(); i++) {
                var r = regions.get(i);
                source.sendFeedback(Component.translatable(
                    "client-tools.csweep.litematica.status_region",
                    i + 1, r.name(),
                    r.pos1().getX(), r.pos1().getY(), r.pos1().getZ(),
                    r.pos2().getX(), r.pos2().getY(), r.pos2().getZ()));
            }
        }

        SweepExecutor exe = SweepExecutor.getInstance();
        if (exe.isRunning() || exe.isPaused() || SweepState.isPaused()) {
            source.sendFeedback(Component.translatable(
                "client-tools.csweep.litematica.current_region",
                exe.getCurrentRegionName(), exe.getCurrentRegionIndex() + 1, exe.getRegionCount()));
        }
        return 1;
    }

    private static int skipToNextRegion(FabricClientCommandSource source) {
        SweepExecutor exe = SweepExecutor.getInstance();
        if (!exe.isRunning() && !exe.isPaused()) {
            source.sendFeedback(Component.translatable(
                "client-tools.csweep.litematica.not_in_sweep"));
            return 0;
        }
        if (exe.skipToNextRegion()) {
            source.sendFeedback(Component.translatable(
                "client-tools.csweep.litematica.region_skipped",
                exe.getCurrentRegionName(), exe.getCurrentRegionIndex() + 1, exe.getRegionCount()));
            return 1;
        } else {
            source.sendFeedback(Component.translatable(
                "client-tools.csweep.litematica.no_next_region"));
            return 0;
        }
    }

    private static int toggleNearestTracking(FabricClientCommandSource source) {
        SweepExecutor exe = SweepExecutor.getInstance();
        return setNearestTracking(source, !exe.isNearestTrackingEnabled());
    }

    private static int setNearestTracking(FabricClientCommandSource source, boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
            return 0;
        }

        SweepExecutor exe = SweepExecutor.getInstance();

        if (enabled == exe.isNearestTrackingEnabled()) {
            // Already in the desired state
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.csweep.nearest_already_on" : "client-tools.csweep.nearest_already_off"));
            return 1;
        }

        if (!enabled) {
            // Turn OFF tracking
            exe.setNearestTrackingEnabled(false);
            exe.clearNearestStation();
            source.sendFeedback(Component.translatable("client-tools.csweep.nearest_off"));
            return 1;
        }

        // Turn ON tracking — stop any running sweep first
        if (exe.isRunning()) {
            exe.stop();
            source.sendFeedback(Component.translatable("client-tools.csweep.stopped"));
        }

        // If there's a paused state, clear it (user is choosing a new start point)
        if (SweepState.isPaused()) {
            SweepState.clearPauseState();
            source.sendFeedback(Component.translatable("client-tools.csweep.nearest_cleared_pause"));
        }

        String info = exe.findNearestStation();
        if (info == null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.pos_not_set"));
            return 0;
        }

        exe.setNearestTrackingEnabled(true);
        source.sendFeedback(Component.translatable("client-tools.csweep.nearest_on", info));
        source.sendFeedback(Component.translatable("client-tools.csweep.nearest_hint"));
        return 1;
    }

    // ==================== autospeed ====================

    private static int toggleAutoSpeed(FabricClientCommandSource source) {
        return setAutoSpeed(source, !SweepState.isAutoSpeed());
    }

    private static int setAutoSpeed(FabricClientCommandSource source, boolean enabled) {
        if (SweepState.isAutoSpeed() == enabled) {
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.csweep.autospeed_already_on" : "client-tools.csweep.autospeed_already_off"));
            return 1;
        }
        SweepState.setAutoSpeed(enabled);
        if (enabled) {
            source.sendFeedback(Component.translatable("client-tools.csweep.autospeed_on",
                SweepState.getSpeed(), SweepState.getMaxSpeed()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.autospeed_off"));
        }
        return 1;
    }

    // ==================== avoidwater ====================

    private static int toggleAvoidWater(FabricClientCommandSource source) {
        return setAvoidWater(source, !SweepState.isAvoidWater());
    }

    private static int setAvoidWater(FabricClientCommandSource source, boolean enabled) {
        if (SweepState.isAvoidWater() == enabled) {
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.csweep.avoidwater_already_on" : "client-tools.csweep.avoidwater_already_off"));
            return 1;
        }
        SweepState.setAvoidWater(enabled);
        source.sendFeedback(Component.translatable(
            enabled ? "client-tools.csweep.avoidwater_on" : "client-tools.csweep.avoidwater_off"));
        return 1;
    }

    // ==================== blockdetect ====================

    private static int toggleBlockageDetection(FabricClientCommandSource source) {
        return setBlockageDetection(source, !SweepState.isBlockageDetection());
    }

    private static int setBlockageDetection(FabricClientCommandSource source, boolean enabled) {
        if (SweepState.isBlockageDetection() == enabled) {
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.csweep.blockdetect_already_on" : "client-tools.csweep.blockdetect_already_off"));
            return 1;
        }
        SweepState.setBlockageDetection(enabled);
        if (enabled) {
            String mode = SweepState.isBlockageStop()
                ? Component.translatable("client-tools.csweep.blockdetect_mode_wait").getString()
                : Component.translatable("client-tools.csweep.blockdetect_mode_slow").getString();
            source.sendFeedback(Component.translatable("client-tools.csweep.blockdetect_on", mode));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.blockdetect_off"));
        }
        return 1;
    }

    private static int setBlockageStop(FabricClientCommandSource source, boolean waitMode) {
        if (SweepState.isBlockageStop() == waitMode) {
            String mode = waitMode
                ? Component.translatable("client-tools.csweep.blockdetect_mode_wait").getString()
                : Component.translatable("client-tools.csweep.blockdetect_mode_slow").getString();
            source.sendFeedback(Component.translatable("client-tools.csweep.blockdetect_mode_already", mode));
            return 1;
        }
        SweepState.setBlockageStop(waitMode);
        if (waitMode) {
            source.sendFeedback(Component.translatable("client-tools.csweep.blockdetect_mode_set_wait"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.blockdetect_mode_set_slow"));
        }
        // Auto-enable blockage detection if setting a mode explicitly
        if (!SweepState.isBlockageDetection()) {
            SweepState.setBlockageDetection(true);
            source.sendFeedback(Component.translatable("client-tools.csweep.blockdetect_auto_on"));
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

    // ==================== maxspeed ====================

    private static int setMaxSpeed(FabricClientCommandSource source, double bpt) {
        SweepState.setMaxSpeed(bpt);
        source.sendFeedback(Component.translatable("client-tools.csweep.maxspeed_set", bpt));
        return 1;
    }

    private static int showMaxSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.csweep.maxspeed_show", SweepState.getMaxSpeed()));
        return 1;
    }

    // ==================== show ====================

    private static int toggleOutline(FabricClientCommandSource source) {
        return setOutline(source, !SweepState.isShowOutline());
    }

    private static int setOutline(FabricClientCommandSource source, boolean enabled) {
        if (SweepState.isShowOutline() == enabled) {
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.csweep.show_outline_already_on" : "client-tools.csweep.show_outline_already_off"));
            return 1;
        }
        SweepState.setShowOutline(enabled);
        source.sendFeedback(Component.translatable(
            enabled ? "client-tools.csweep.show_outline_on" : "client-tools.csweep.show_outline_off"));
        return 1;
    }

    private static int togglePath(FabricClientCommandSource source) {
        return setPath(source, !SweepState.isShowPath());
    }

    private static int setPath(FabricClientCommandSource source, boolean enabled) {
        if (SweepState.isShowPath() == enabled) {
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.csweep.show_path_already_on" : "client-tools.csweep.show_path_already_off"));
            return 1;
        }
        SweepState.setShowPath(enabled);
        source.sendFeedback(Component.translatable(
            enabled ? "client-tools.csweep.show_path_on" : "client-tools.csweep.show_path_off"));
        return 1;
    }

    private static int toggleLayer(FabricClientCommandSource source) {
        return setLayer(source, !SweepState.isHighlightCurrentLayer());
    }

    private static int setLayer(FabricClientCommandSource source, boolean enabled) {
        if (SweepState.isHighlightCurrentLayer() == enabled) {
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.csweep.show_layer_already_on" : "client-tools.csweep.show_layer_already_off"));
            return 1;
        }
        SweepState.setHighlightCurrentLayer(enabled);
        source.sendFeedback(Component.translatable(
            enabled ? "client-tools.csweep.show_layer_on" : "client-tools.csweep.show_layer_off"));
        return 1;
    }

    private static int toggleNearestDirection(FabricClientCommandSource source) {
        return setNearestDirection(source, !SweepState.isShowNearestDirection());
    }

    private static int setNearestDirection(FabricClientCommandSource source, boolean enabled) {
        if (SweepState.isShowNearestDirection() == enabled) {
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.csweep.show_dir_already_on" : "client-tools.csweep.show_dir_already_off"));
            return 1;
        }
        SweepState.setShowNearestDirection(enabled);
        source.sendFeedback(Component.translatable(
            enabled ? "client-tools.csweep.show_dir_on" : "client-tools.csweep.show_dir_off"));
        return 1;
    }

    private static int showDisplayStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.csweep.show_status",
            SweepState.isShowOutline() ? "§aON" : "§7OFF",
            SweepState.isShowPath() ? "§aON" : "§7OFF",
            SweepState.isHighlightCurrentLayer() ? "§aON" : "§7OFF",
            SweepState.isShowNearestDirection() ? "§aON" : "§7OFF"));
        return 1;
    }

    // ==================== start / stop / pause ====================

    private static int startSweep(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
            return 0;
        }

        SweepExecutor executor = SweepExecutor.getInstance();

        // If a nearest station is active (tracking or one-shot), start from there
        if (executor.hasNearestStation()) {
            // If tracking was on, turn it off — nearest station is consumed in start()
            if (executor.isNearestTrackingEnabled()) {
                executor.setNearestTrackingEnabled(false);
            }
            if (executor.isRunning()) executor.stop();
            // Fall through to start below (nearest station is consumed in start())
        } else if (SweepState.isPaused()) {
            // If there's a saved paused state, resume instead
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
        if (executor.isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.already_running"));
            return 0;
        }

        // Auto-enable highlight during operation
        if (!SweepState.isShowOutline()) SweepState.setShowOutline(true);
        if (!SweepState.isShowPath()) SweepState.setShowPath(true);

        SweepExecutor.getInstance().start();
        // Total stations are computed during start, estimate now
        double spacing = Math.max(1.0, SweepState.getRadius() * 1.5);
        var subRegions = SweepState.resolveSubRegions();
        int estStations = 0;
        long volume = 0;
        for (var box : subRegions) {
            int bx1 = Math.min(box.pos1().getX(), box.pos2().getX());
            int bx2 = Math.max(box.pos1().getX(), box.pos2().getX());
            int by1 = Math.min(box.pos1().getY(), box.pos2().getY());
            int by2 = Math.max(box.pos1().getY(), box.pos2().getY());
            int bz1 = Math.min(box.pos1().getZ(), box.pos2().getZ());
            int bz2 = Math.max(box.pos1().getZ(), box.pos2().getZ());
            int sdx = bx2 - bx1;
            int sdy = by2 - by1;
            int sdz = bz2 - bz1;
            double estRadius = spacing / 1.5;
            int nx = 1 + (int) Math.ceil(Math.max(0, sdx - estRadius) / spacing);
            int ny = 1 + (int) Math.ceil(Math.max(0, sdy - estRadius) / spacing);
            int nz = 1 + (int) Math.ceil(Math.max(0, sdz - estRadius) / spacing);
            estStations += nx * ny * nz;
            volume += (long) (sdx + 1) * (sdy + 1) * (sdz + 1);
        }
        String volumeStr = volume >= 1_000_000 ? String.format("%.1fM", volume / 1_000_000.0)
            : volume >= 1_000 ? String.format("%.1fK", volume / 1_000.0)
            : String.valueOf(volume);
        if (SweepExecutor.getInstance().getCurrentStationIndex() > 1) {
            source.sendFeedback(Component.translatable("client-tools.csweep.start_nearest",
                SweepExecutor.getInstance().getCurrentStationIndex(), estStations, volumeStr));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.start", estStations, volumeStr));
        }
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

        // Sync source
        source.sendFeedback(Component.translatable("client-tools.csweep.litematica.status_sync",
            SweepState.isSyncLitematica() ? "§aLitematica" : "§7Manual"));

        // Region count
        var regions = SweepState.resolveSubRegions();
        source.sendFeedback(Component.translatable("client-tools.csweep.status_regions", regions.size()));

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
        source.sendFeedback(Component.translatable("client-tools.csweep.status_maxspeed", SweepState.getMaxSpeed()));
        source.sendFeedback(Component.translatable("client-tools.csweep.status_autospeed",
            SweepState.isAutoSpeed() ? "§aON" : "§7OFF"));
        source.sendFeedback(Component.translatable("client-tools.csweep.status_blockdetect",
            SweepState.isBlockageDetection() ? "§aON" : "§7OFF",
            SweepState.isBlockageStop()
                ? Component.translatable("client-tools.csweep.blockdetect_mode_wait").getString()
                : Component.translatable("client-tools.csweep.blockdetect_mode_slow").getString()));
        source.sendFeedback(Component.translatable("client-tools.csweep.status_avoidwater",
            SweepState.isAvoidWater() ? "§aON" : "§7OFF"));

        source.sendFeedback(Component.translatable("client-tools.csweep.status_show",
            SweepState.isShowOutline() ? "§aON" : "§7OFF",
            SweepState.isShowPath() ? "§aON" : "§7OFF",
            SweepState.isHighlightCurrentLayer() ? "§aON" : "§7OFF",
            SweepState.isShowNearestDirection() ? "§aON" : "§7OFF"));

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
            sendProgressBar(source, executor);
        } else if (SweepState.isPaused()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_paused_saved",
                SweepState.getSavedStationIndex() + 1));
        } else if (executor.isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_running",
                executor.getCurrentStationIndex() + 1, executor.getTotalStations()));
            sendProgressBar(source, executor);
            sendPenaltyBrief(source, executor);
        } else if (executor.isDone()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_done", executor.getTotalStations()));
        } else if (executor.isError()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_error", executor.getErrorMessage()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csweep.status_idle"));
        }

        return 1;
    }

    private static void sendProgressBar(FabricClientCommandSource source, SweepExecutor executor) {
        double totalDist = executor.getTotalAllRegionsLength();
        if (totalDist <= 0) return;

        double completedDist = executor.getCompletedRegionsLength()
            + (executor.isApproaching() ? 0 : executor.getDistanceTraveled());
        double fraction = Math.max(0.0, Math.min(1.0, completedDist / totalDist));
        double pct = fraction * 100.0;

        int barWidth = 20;
        int filled = (int) Math.round(fraction * barWidth);
        if (filled > barWidth) filled = barWidth;
        int empty = barWidth - filled;

        StringBuilder bar = new StringBuilder();
        bar.append("§a");
        for (int i = 0; i < filled; i++) bar.append('█');
        bar.append("§7");
        for (int i = 0; i < empty; i++) bar.append('░');
        bar.append("§r");

        double speed = SweepState.getSpeed();
        double remainingDist = totalDist - completedDist;
        long etaSeconds = speed > 0 ? (long) Math.ceil(remainingDist / speed) : 0;

        source.sendFeedback(Component.translatable("client-tools.csweep.status_progress",
            bar.toString(), String.format("%.1f", pct), formatEta(etaSeconds)));
    }

    private static String formatEta(long totalSeconds) {
        if (totalSeconds >= 3600) {
            long h = totalSeconds / 3600;
            long m = (totalSeconds % 3600) / 60;
            return h + "h " + m + "m";
        } else if (totalSeconds >= 60) {
            long m = totalSeconds / 60;
            long s = totalSeconds % 60;
            return m + "m " + s + "s";
        } else {
            return totalSeconds + "s";
        }
    }

    // ==================== penalty ====================

    private static int showPenalty(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
            return 0;
        }

        SweepExecutor executor = SweepExecutor.getInstance();
        SweepExecutor.SweepPenaltyStatus p = executor.getPenaltyStatus(client);
        if (p == null) {
            source.sendFeedback(Component.translatable("client-tools.csweep.not_in_world"));
            return 0;
        }

        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_header"));

        // --- Flight / onGround section ---
        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_flying",
            p.isFlying() ? "§aYES" : "§7NO"));
        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_onground",
            p.actualOnGround() ? "§atrue" : "§efalse",
            p.spoofedOnGround() ? "§atrue" : "§efalse"));
        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_block_below",
            p.hasBlockBelow() ? "§aYES" : "§cNO"));

        // Explain the floating penalty if applicable
        if (p.isFlying() && !p.actualOnGround() && !p.hasBlockBelow()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.penalty_floating_warn"));
        }
        if (p.spoofedOnGround() != p.actualOnGround()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.penalty_spoof_note"));
        }

        // --- Water ---
        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_water",
            p.inWater() ? "§bYES §7(5×–25×)" : "§7NO"));
        if (p.inWater()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.penalty_water_detail"));
        }

        // --- Cobweb ---
        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_cobweb",
            p.inCobweb() ? "§8YES §7(25%)" : "§7NO"));

        // --- Stuck ---
        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_stuck",
            p.stuckInBlock() ? "§cYES" : "§7NO"));

        // --- Movement blocked ---
        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_blocked",
            p.blockedTicks() >= p.blockedTickThreshold()
                ? "§c" + p.blockedTicks() + " ticks ≥ " + p.blockedTickThreshold()
                : "§7" + p.blockedTicks() + " / " + p.blockedTickThreshold()));

        // --- Backward blockage ---
        if (p.blockageDetection()) {
            source.sendFeedback(Component.translatable("client-tools.csweep.penalty_blockage",
                p.blockageCount(),
                p.blockageThreshold(),
                p.blockageCount() > p.blockageThreshold()
                    ? (p.blockageStop() ? "§cSTOP" : "§eSLOW 20%")
                    : "§7OK"));
        }

        // --- Speed summary ---
        String speedLine;
        if (p.autoSpeed()) {
            speedLine = Component.translatable("client-tools.csweep.penalty_speed_auto",
                String.format("%.1f", p.configuredSpeed()),
                String.format("%.1f", SweepState.getMaxSpeed()),
                String.format("%.1f", p.effectiveSpeed()),
                String.format("%.2f", p.density())).getString();
        } else {
            speedLine = Component.translatable("client-tools.csweep.penalty_speed_fixed",
                String.format("%.1f", p.configuredSpeed()),
                String.format("%.1f", p.effectiveSpeed())).getString();
        }
        source.sendFeedback(Component.literal(speedLine));

        // --- Flags ---
        source.sendFeedback(Component.translatable("client-tools.csweep.penalty_flags",
            p.avoidWater() ? "§aON" : "§7OFF",
            p.blockageDetection() ? "§aON" : "§7OFF",
            p.isRunning() ? "§aRUNNING" : "§7IDLE"));

        return 1;
    }

    /** One-line penalty summary appended to /csweep status when running. */
    private static void sendPenaltyBrief(FabricClientCommandSource source, SweepExecutor executor) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        SweepExecutor.SweepPenaltyStatus p = executor.getPenaltyStatus(client);
        if (p == null) return;

        StringBuilder brief = new StringBuilder();
        if (p.inWater()) brief.append(" §3水");
        if (p.isFlying() && !p.actualOnGround() && !p.hasBlockBelow()) brief.append(" §7浮空");
        if (p.inCobweb()) brief.append(" §8蛛网");
        if (p.stuckInBlock()) brief.append(" §4卡方块");
        if (p.blockedTicks() >= p.blockedTickThreshold()) brief.append(" §c移阻");
        if (p.blockageDetection() && p.blockageCount() > p.blockageThreshold())
            brief.append(" §6后方").append(p.blockageCount());

        if (brief.length() > 0) {
            source.sendFeedback(Component.translatable("client-tools.csweep.penalty_brief", brief.toString()));
        }
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
