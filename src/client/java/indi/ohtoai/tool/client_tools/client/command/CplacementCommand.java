package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.sweep.LitematicaIntegration;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Registers the {@code /cplacement} command for moving
 * Litematica schematic placements/selections along individual axes.
 *
 * <pre>
 * /cplacement
 *   x &lt;amount&gt;           — move along X axis (positive = east)
 *   y &lt;amount&gt;           — move along Y axis (positive = up)
 *   z &lt;amount&gt;           — move along Z axis (positive = south)
 *   status                  — show current selection info
 *   help [subcommand]       — show help
 * </pre>
 *
 * <p>The command requires Litematica to be installed.
 * If Litematica is not present, all subcommands report the unavailability.</p>
 */
public class CplacementCommand {

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

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"x", "y", "z", "status"}) {
                if (s.toLowerCase().startsWith(remaining)) builder.suggest(s);
            }
            return builder.buildFuture();
        };

    // --- Registration ---

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("cplacement")
                .executes(ctx -> showBriefHelp(ctx.getSource()))
                // /cplacement help [subcommand]
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand")))))
                // /cplacement x <amount>
                .then(literal("x")
                    .then(argument("amount", IntegerArgumentType.integer())
                        .suggests(OFFSET_SUGGESTIONS)
                        .executes(ctx -> moveAxis(ctx.getSource(), "x",
                            IntegerArgumentType.getInteger(ctx, "amount")))))
                // /cplacement y <amount>
                .then(literal("y")
                    .then(argument("amount", IntegerArgumentType.integer())
                        .suggests(OFFSET_SUGGESTIONS)
                        .executes(ctx -> moveAxis(ctx.getSource(), "y",
                            IntegerArgumentType.getInteger(ctx, "amount")))))
                // /cplacement z <amount>
                .then(literal("z")
                    .then(argument("amount", IntegerArgumentType.integer())
                        .suggests(OFFSET_SUGGESTIONS)
                        .executes(ctx -> moveAxis(ctx.getSource(), "z",
                            IntegerArgumentType.getInteger(ctx, "amount")))))
                // /cplacement status
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
        );
    }

    // --- Help ---

    private static int showBriefHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.cplacement.help.brief"));
        return 1;
    }

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.cplacement.help.header"));
        source.sendFeedback(Component.translatable("client-tools.cplacement.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.cplacement.help.x"));
        source.sendFeedback(Component.translatable("client-tools.cplacement.help.y"));
        source.sendFeedback(Component.translatable("client-tools.cplacement.help.z"));
        source.sendFeedback(Component.translatable("client-tools.cplacement.help.status"));
        source.sendFeedback(Component.translatable("client-tools.cplacement.help.example"));
        if (!LitematicaIntegration.isAvailable()) {
            source.sendFeedback(Component.translatable("client-tools.cplacement.litematica_not_available"));
        }
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "x" -> source.sendFeedback(Component.translatable("client-tools.cplacement.help.x_detail"));
            case "y" -> source.sendFeedback(Component.translatable("client-tools.cplacement.help.y_detail"));
            case "z" -> source.sendFeedback(Component.translatable("client-tools.cplacement.help.z_detail"));
            case "status" -> source.sendFeedback(Component.translatable("client-tools.cplacement.help.status_detail"));
            default -> source.sendFeedback(Component.translatable("client-tools.cplacement.help.unknown_subcommand", subcommand));
        }
        return 1;
    }

    // --- Move ---

    private static int moveAxis(FabricClientCommandSource source, String axis, int amount) {
        if (!LitematicaIntegration.isAvailable()) {
            source.sendFeedback(Component.translatable("client-tools.cplacement.litematica_not_available"));
            return 0;
        }
        if (amount == 0) {
            source.sendFeedback(Component.translatable("client-tools.cplacement.zero_offset"));
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
                source.sendFeedback(Component.translatable("client-tools.cplacement.invalid_axis", axis));
                return 0;
        }

        int count = LitematicaIntegration.moveSelection(dx, dy, dz);
        if (count < 0) {
            source.sendFeedback(Component.translatable("client-tools.cplacement.move_failed"));
            return 0;
        }
        if (count == 0) {
            source.sendFeedback(Component.translatable("client-tools.cplacement.no_selection"));
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
        source.sendFeedback(Component.translatable("client-tools.cplacement.moved",
            count, Math.abs(amount), label));
        return 1;
    }

    // --- Status ---

    private static int showStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.cplacement.status_header"));

        if (!LitematicaIntegration.isAvailable()) {
            String err = LitematicaIntegration.getInitError();
            source.sendFeedback(Component.translatable("client-tools.cplacement.litematica_not_available"));
            if (err != null && !err.isEmpty()) {
                source.sendFeedback(Component.literal("§7  (" + err + ")"));
            }
            return 1;
        }

        source.sendFeedback(Component.translatable("client-tools.cplacement.status_litematica",
            Component.translatable("client-tools.cplacement.status_available")));

        List<LitematicaIntegration.SubRegionBox> regions = LitematicaIntegration.getSubRegions();
        if (regions.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.cplacement.status_no_selection"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.cplacement.status_regions", regions.size()));
            for (int i = 0; i < regions.size(); i++) {
                LitematicaIntegration.SubRegionBox box = regions.get(i);
                BlockPos p1 = box.pos1();
                BlockPos p2 = box.pos2();
                source.sendFeedback(Component.translatable("client-tools.cplacement.status_region",
                    i + 1, box.name(),
                    p1.getX(), p1.getY(), p1.getZ(),
                    p2.getX(), p2.getY(), p2.getZ()));
            }
        }
        return 1;
    }
}
