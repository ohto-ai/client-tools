package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.follow.FollowExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.LitematicaIntegration;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Registers the {@code /clitematica} command for Litematica selection
 * manipulation — shifting and player-following.
 *
 * <pre>
 * /clitematica
 *   shift x &lt;amount&gt;           — move along X axis (positive = east)
 *   shift y &lt;amount&gt;           — move along Y axis (positive = up)
 *   shift z &lt;amount&gt;           — move along Z axis (positive = south)
 *   shift status                 — show current selection info
 *   follow start                 — start following the player
 *   follow stop                  — stop following
 *   follow status                — show follow state (offset, axes)
 *   follow axis &lt;x|y|z&gt; &lt;lock|unlock&gt; — lock/unlock an axis
 *   help [subcommand]            — show help
 * </pre>
 */
public class ClitematicaCommand {

    // --- Suggestion providers ---

    private static final SuggestionProvider<FabricClientCommandSource> OFFSET_SUGGESTIONS =
        (ctx, builder) -> {
            builder.suggest("1");
            builder.suggest("-1");
            builder.suggest("5");
            builder.suggest("-5");
            builder.suggest("10");
            builder.suggest("50");
            builder.suggest("100");
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> AXIS_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"x", "y", "z"}) {
                if (s.toLowerCase().startsWith(remaining)) builder.suggest(s);
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> LOCK_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"lock", "unlock"}) {
                if (s.toLowerCase().startsWith(remaining)) builder.suggest(s);
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"shift", "follow"}) {
                if (s.toLowerCase().startsWith(remaining)) builder.suggest(s);
            }
            return builder.buildFuture();
        };

    // --- Registration ---

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("clitematica")
                .executes(ctx -> showBriefHelp(ctx.getSource()))
                // /clitematica help [subcommand]
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand")))))
                // ===== shift =====
                .then(literal("shift")
                    .executes(ctx -> showShiftBriefHelp(ctx.getSource()))
                    // /clitematica shift x <amount>
                    .then(literal("x")
                        .then(argument("amount", IntegerArgumentType.integer())
                            .suggests(OFFSET_SUGGESTIONS)
                            .executes(ctx -> moveAxis(ctx.getSource(), "x",
                                IntegerArgumentType.getInteger(ctx, "amount")))))
                    // /clitematica shift y <amount>
                    .then(literal("y")
                        .then(argument("amount", IntegerArgumentType.integer())
                            .suggests(OFFSET_SUGGESTIONS)
                            .executes(ctx -> moveAxis(ctx.getSource(), "y",
                                IntegerArgumentType.getInteger(ctx, "amount")))))
                    // /clitematica shift z <amount>
                    .then(literal("z")
                        .then(argument("amount", IntegerArgumentType.integer())
                            .suggests(OFFSET_SUGGESTIONS)
                            .executes(ctx -> moveAxis(ctx.getSource(), "z",
                                IntegerArgumentType.getInteger(ctx, "amount")))))
                    // /clitematica shift status
                    .then(literal("status")
                        .executes(ctx -> showShiftStatus(ctx.getSource()))))
                // ===== follow =====
                .then(literal("follow")
                    .executes(ctx -> showFollowBriefHelp(ctx.getSource()))
                    // /clitematica follow start
                    .then(literal("start")
                        .executes(ctx -> doStart(ctx.getSource())))
                    // /clitematica follow stop
                    .then(literal("stop")
                        .executes(ctx -> doStop(ctx.getSource())))
                    // /clitematica follow status
                    .then(literal("status")
                        .executes(ctx -> showFollowStatus(ctx.getSource())))
                    // /clitematica follow axis <x|y|z> <lock|unlock>
                    .then(literal("axis")
                        .then(argument("axis", StringArgumentType.word())
                            .suggests(AXIS_SUGGESTIONS)
                            .then(argument("lock", StringArgumentType.word())
                                .suggests(LOCK_SUGGESTIONS)
                                .executes(ctx -> doAxis(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "axis"),
                                    StringArgumentType.getString(ctx, "lock")))))))
        );
    }

    // ==================== Help ====================

    private static int showBriefHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.clitematica.help.brief"));
        return 1;
    }

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.clitematica.help.header"));
        source.sendFeedback(Component.translatable("client-tools.clitematica.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.clitematica.help.shift"));
        source.sendFeedback(Component.translatable("client-tools.clitematica.help.follow"));
        source.sendFeedback(Component.translatable("client-tools.clitematica.help.example"));
        if (!LitematicaIntegration.isAvailable()) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.litematica_not_available"));
        }
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "shift" -> source.sendFeedback(Component.translatable("client-tools.clitematica.help.shift_detail"));
            case "follow" -> source.sendFeedback(Component.translatable("client-tools.clitematica.help.follow_detail"));
            default -> source.sendFeedback(Component.translatable("client-tools.clitematica.help.unknown_subcommand", subcommand));
        }
        return 1;
    }

    // ==================== Shift ====================

    private static int showShiftBriefHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.clitematica.help.shift_brief"));
        return 1;
    }

    private static int moveAxis(FabricClientCommandSource source, String axis, int amount) {
        if (!LitematicaIntegration.isAvailable()) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.litematica_not_available"));
            return 0;
        }
        if (amount == 0) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.shift.zero_offset"));
            return 0;
        }

        int dx = 0, dy = 0, dz = 0;
        String dirLabel;
        switch (axis) {
            case "x":
                dx = amount;
                dirLabel = amount > 0 ? "east" : "west";
                break;
            case "y":
                dy = amount;
                dirLabel = amount > 0 ? "up" : "down";
                break;
            case "z":
                dz = amount;
                dirLabel = amount > 0 ? "south" : "north";
                break;
            default:
                source.sendFeedback(Component.translatable("client-tools.clitematica.shift.invalid_axis", axis));
                return 0;
        }

        int count = LitematicaIntegration.moveSelection(dx, dy, dz);
        if (count < 0) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.shift.move_failed"));
            return 0;
        }
        if (count == 0) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.shift.no_selection"));
            return 0;
        }

        String label = switch (dirLabel) {
            case "east" -> "+X (east)";
            case "west" -> "-X (west)";
            case "up" -> "+Y (up)";
            case "down" -> "-Y (down)";
            case "south" -> "+Z (south)";
            case "north" -> "-Z (north)";
            default -> dirLabel;
        };
        source.sendFeedback(Component.translatable("client-tools.clitematica.shift.moved",
            count, Math.abs(amount), label));
        return 1;
    }

    private static int showShiftStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.clitematica.shift.status_header"));

        if (!LitematicaIntegration.isAvailable()) {
            String err = LitematicaIntegration.getInitError();
            source.sendFeedback(Component.translatable("client-tools.clitematica.litematica_not_available"));
            if (err != null && !err.isEmpty()) {
                source.sendFeedback(Component.literal("§7  (" + err + ")"));
            }
            return 1;
        }

        source.sendFeedback(Component.translatable("client-tools.clitematica.shift.status_litematica",
            Component.translatable("client-tools.clitematica.shift.status_available")));

        List<LitematicaIntegration.SubRegionBox> regions = LitematicaIntegration.getSubRegions();
        if (regions.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.shift.status_no_selection"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.clitematica.shift.status_regions", regions.size()));
            for (int i = 0; i < regions.size(); i++) {
                LitematicaIntegration.SubRegionBox box = regions.get(i);
                BlockPos p1 = box.pos1();
                BlockPos p2 = box.pos2();
                source.sendFeedback(Component.translatable("client-tools.clitematica.shift.status_region",
                    i + 1, box.name(),
                    p1.getX(), p1.getY(), p1.getZ(),
                    p2.getX(), p2.getY(), p2.getZ()));
            }
        }
        return 1;
    }

    // ==================== Follow ====================

    private static int showFollowBriefHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.clitematica.help.follow_brief"));
        return 1;
    }

    private static int doStart(FabricClientCommandSource source) {
        String errorKey = FollowExecutor.getInstance().start();
        if (errorKey != null) {
            switch (errorKey) {
                case "litematica_not_available" ->
                    source.sendFeedback(Component.translatable("client-tools.clitematica.litematica_not_available"));
                case "no_selection" ->
                    source.sendFeedback(Component.translatable("client-tools.clitematica.follow.no_selection"));
                case "already_following" ->
                    source.sendFeedback(Component.translatable("client-tools.clitematica.follow.already_following"));
                case "player_not_available" ->
                    source.sendFeedback(Component.translatable("client-tools.clitematica.follow.player_not_available"));
                default ->
                    source.sendFeedback(Component.translatable("client-tools.clitematica.follow.start_failed", errorKey));
            }
            return 0;
        }

        Vec3 offset = FollowExecutor.getInstance().getOffset();
        String offsetStr = String.format("(%.1f, %.1f, %.1f)", offset.x, offset.y, offset.z);
        source.sendFeedback(Component.translatable("client-tools.clitematica.follow.started", offsetStr));
        return 1;
    }

    private static int doStop(FabricClientCommandSource source) {
        String errorKey = FollowExecutor.getInstance().stop();
        if (errorKey != null) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.not_following"));
            return 0;
        }
        source.sendFeedback(Component.translatable("client-tools.clitematica.follow.stopped"));
        return 1;
    }

    private static int doAxis(FabricClientCommandSource source, String axis, String lockStr) {
        axis = axis.toLowerCase();
        if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.invalid_axis", axis));
            return 0;
        }

        boolean locked;
        switch (lockStr.toLowerCase()) {
            case "lock" -> locked = true;
            case "unlock" -> locked = false;
            default -> {
                source.sendFeedback(Component.translatable("client-tools.clitematica.follow.invalid_lock", lockStr));
                return 0;
            }
        }

        String errorKey = FollowExecutor.getInstance().setAxisLock(axis, locked);
        if (errorKey != null) {
            switch (errorKey) {
                case "axis_already_locked" ->
                    source.sendFeedback(Component.translatable("client-tools.clitematica.follow.axis_already_locked", axis.toUpperCase()));
                case "axis_already_unlocked" ->
                    source.sendFeedback(Component.translatable("client-tools.clitematica.follow.axis_already_unlocked", axis.toUpperCase()));
                default ->
                    source.sendFeedback(Component.translatable("client-tools.clitematica.follow.axis_error", errorKey));
            }
            return 0;
        }

        if (locked) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.axis_locked", axis.toUpperCase()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.axis_unlocked", axis.toUpperCase()));
        }
        return 1;
    }

    private static int showFollowStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.clitematica.follow.status_header"));

        if (!LitematicaIntegration.isAvailable()) {
            String err = LitematicaIntegration.getInitError();
            source.sendFeedback(Component.translatable("client-tools.clitematica.litematica_not_available"));
            if (err != null && !err.isEmpty()) {
                source.sendFeedback(Component.literal("§7  (" + err + ")"));
            }
            return 1;
        }

        FollowExecutor executor = FollowExecutor.getInstance();

        if (!executor.isFollowing()) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.status_idle"));
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.status_axes",
                lockLabel(executor.isLockX()),
                lockLabel(executor.isLockY()),
                lockLabel(executor.isLockZ())));
            return 1;
        }

        source.sendFeedback(Component.translatable("client-tools.clitematica.follow.status_following"));

        Vec3 offset = executor.getOffset();
        if (offset != null) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.status_offset",
                String.format("%.1f", offset.x),
                String.format("%.1f", offset.y),
                String.format("%.1f", offset.z)));
        }

        Vec3 refPlayer = executor.getReferencePlayerPos();
        if (refPlayer != null) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.status_ref_player",
                String.format("%.1f", refPlayer.x),
                String.format("%.1f", refPlayer.y),
                String.format("%.1f", refPlayer.z)));
        }

        Vec3 refSel = executor.getReferenceSelectionPos();
        if (refSel != null) {
            source.sendFeedback(Component.translatable("client-tools.clitematica.follow.status_ref_selection",
                refSel.x, refSel.y, refSel.z));
        }

        source.sendFeedback(Component.translatable("client-tools.clitematica.follow.status_axes",
            lockLabel(executor.isLockX()),
            lockLabel(executor.isLockY()),
            lockLabel(executor.isLockZ())));

        return 1;
    }

    private static String lockLabel(boolean locked) {
        return locked ? "§cLOCKED" : "§aunlocked";
    }
}
