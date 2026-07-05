package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.attack.AttackExecutor;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * {@code /cattack} — auto-attack entities on a configurable interval.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code start interval <duration> <selector>} — start auto-attacking</li>
 *   <li>{@code stop} — stop auto-attacking</li>
 *   <li>{@code status} — show current state</li>
 *   <li>{@code help [subcommand]} — help text</li>
 * </ul>
 */
public class CattackCommand {

    private static final SuggestionProvider<FabricClientCommandSource> SELECTOR_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"@p", "@a", "@r", "@e[distance=..5,limit=1]", "@e[type=minecraft:zombie,distance=..5,limit=1]", "@e[sort=nearest,limit=1]"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> DURATION_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"1s", "5s", "10t", "500ms", "1m", "30s"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"start", "stop", "status"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("cattack")
                .executes(ctx -> showBriefHelp(ctx.getSource()))
                // /cattack help [subcommand]
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand")))))
                // /cattack stop
                .then(literal("stop")
                    .executes(ctx -> stop(ctx.getSource())))
                // /cattack status
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
                // /cattack start interval <duration> <selector>
                .then(literal("start")
                    .then(literal("interval")
                        .then(argument("duration", StringArgumentType.word())
                            .suggests(DURATION_SUGGESTIONS)
                            .then(argument("selector", StringArgumentType.greedyString())
                                .suggests(SELECTOR_SUGGESTIONS)
                                .executes(ctx -> start(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "selector"),
                                    StringArgumentType.getString(ctx, "duration")))))))
        );
    }

    // --- Start ---

    private static int start(FabricClientCommandSource source, String selector, String durationStr) {
        int intervalTicks = CtimerCommand.parseDuration(durationStr);
        if (intervalTicks <= 0) {
            source.sendFeedback(Component.translatable("client-tools.cattack.duration_invalid", durationStr));
            return 0;
        }

        String error = AttackExecutor.getInstance().start(selector, intervalTicks);
        if (error != null) {
            switch (error) {
                case "not_connected" ->
                    source.sendFeedback(Component.translatable("client-tools.cattack.not_connected"));
                case "already_running" ->
                    source.sendFeedback(Component.translatable("client-tools.cattack.already_running"));
                case "self_target" ->
                    source.sendFeedback(Component.translatable("client-tools.cattack.self_target"));
                case "invalid_selector" ->
                    source.sendFeedback(Component.translatable("client-tools.cattack.invalid_selector", selector));
                case "interval_invalid" ->
                    source.sendFeedback(Component.translatable("client-tools.cattack.interval_invalid"));
                default ->
                    source.sendFeedback(Component.translatable("client-tools.cattack.error", error));
            }
            return 0;
        }

        String targetName = AttackExecutor.getInstance().getTargetDisplayName();
        source.sendFeedback(Component.translatable("client-tools.cattack.started",
            targetName.isEmpty() ? selector : targetName,
            CtimerCommand.formatDuration(intervalTicks)));
        return 1;
    }

    // --- Stop ---

    private static int stop(FabricClientCommandSource source) {
        String error = AttackExecutor.getInstance().stop();
        if (error != null) {
            source.sendFeedback(Component.translatable("client-tools.cattack.not_running"));
            return 0;
        }
        source.sendFeedback(Component.translatable("client-tools.cattack.stopped"));
        return 1;
    }

    // --- Status ---

    private static int showStatus(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();

        source.sendFeedback(Component.translatable("client-tools.cattack.status_header"));

        if (!AttackExecutor.getInstance().isActive()) {
            source.sendFeedback(Component.translatable("client-tools.cattack.status_idle"));
            return 0;
        }

        String name = AttackExecutor.getInstance().getTargetDisplayName();
        String selector = AttackExecutor.getInstance().getSelector();
        String label = name.isEmpty() ? selector : name;
        String interval = CtimerCommand.formatDuration(AttackExecutor.getInstance().getIntervalTicks());
        int attacks = AttackExecutor.getInstance().getTotalAttacks();

        source.sendFeedback(Component.translatable("client-tools.cattack.status_running"));
        source.sendFeedback(Component.translatable("client-tools.cattack.status_target", label, selector));
        source.sendFeedback(Component.translatable("client-tools.cattack.status_interval", interval));
        source.sendFeedback(Component.translatable("client-tools.cattack.status_attacks", attacks));

        if (client.player == null) {
            source.sendFeedback(Component.translatable("client-tools.cattack.not_connected"));
            return 0;
        }

        return 1;
    }

    // --- Help ---

    private static int showBriefHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.cattack.help.brief"));
        return 1;
    }

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.cattack.help.header"));
        source.sendFeedback(Component.translatable("client-tools.cattack.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.cattack.help.start"));
        source.sendFeedback(Component.translatable("client-tools.cattack.help.stop"));
        source.sendFeedback(Component.translatable("client-tools.cattack.help.status"));
        source.sendFeedback(Component.translatable("client-tools.cattack.help.duration_format"));
        source.sendFeedback(Component.translatable("client-tools.cattack.help.example"));
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "start" -> {
                source.sendFeedback(Component.translatable("client-tools.cattack.help.start_detail"));
                source.sendFeedback(Component.translatable("client-tools.cattack.help.start_example"));
            }
            case "stop" -> {
                source.sendFeedback(Component.translatable("client-tools.cattack.help.stop_detail"));
            }
            case "status" -> {
                source.sendFeedback(Component.translatable("client-tools.cattack.help.status_detail"));
            }
            default -> source.sendFeedback(Component.translatable("client-tools.cattack.help.unknown_subcommand", subcommand));
        }
        return 1;
    }
}
