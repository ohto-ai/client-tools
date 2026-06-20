package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import indi.ohtoai.tool.client_tools.client.timer.TimerInstance;
import indi.ohtoai.tool.client_tools.client.timer.TimerManager;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class CtimerCommand {

    // --- Suggestion providers for tab-completion ---

    private static final SuggestionProvider<FabricClientCommandSource> COUNT_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"1", "5", "10", "30", "infinite"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> DURATION_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"1s", "5s", "30s", "1m", "5m", "30m", "1h", "5h", "10t", "100ms", "500ms"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> COMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (var child : ctx.getRootNode().getChildren()) {
                String name = child.getName();
                if (!name.equals("ctimer") && name.toLowerCase().startsWith(remaining)) {
                    builder.suggest(name + " ");
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"start", "stop", "stop-all", "list"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    // --- Public registration method ---

    /**
     * Register the /ctimer command tree with the given dispatcher.
     * Call from {@code ClientCommandRegistrationCallback.EVENT}.
     */
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("ctimer")
                .executes(ctx -> showBriefHelp(ctx.getSource()))
                // /ctimer help [subcommand]
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand")))))
                // /ctimer stop-all
                .then(literal("stop-all")
                    .executes(ctx -> stopAll(ctx.getSource()))
                )
                // /ctimer stop <pattern>
                .then(literal("stop")
                    .then(argument("pattern", StringArgumentType.word())
                        .executes(ctx -> stopPattern(ctx.getSource(),
                            StringArgumentType.getString(ctx, "pattern")))
                    )
                )
                // /ctimer list
                .then(literal("list")
                    .executes(ctx -> listTimers(ctx.getSource()))
                )
                // /ctimer start <count> interval <duration> <command>
                .then(literal("start")
                    .then(argument("count", StringArgumentType.word())
                        .suggests(COUNT_SUGGESTIONS)
                        .then(literal("interval")
                            .then(argument("duration", StringArgumentType.word())
                                .suggests(DURATION_SUGGESTIONS)
                                .then(argument("command", StringArgumentType.greedyString())
                                    .suggests(COMMAND_SUGGESTIONS)
                                    .executes(ctx -> startTimer(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "count"),
                                        StringArgumentType.getString(ctx, "duration"),
                                        StringArgumentType.getString(ctx, "command")))
                                )
                            )
                        )
                    )
                )
        );
    }

    // --- Help executors ---

    private static int showBriefHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.brief"));
        return 1;
    }

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.header"));
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.start"));
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.stop"));
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.stop_all"));
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.list"));
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.duration_format"));
        source.sendFeedback(Component.translatable("client-tools.ctimer.help.example"));
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "start" -> {
                source.sendFeedback(Component.translatable("client-tools.ctimer.help.start_detail"));
                source.sendFeedback(Component.translatable("client-tools.ctimer.help.start_example"));
            }
            case "stop" -> {
                source.sendFeedback(Component.translatable("client-tools.ctimer.help.stop_detail"));
                source.sendFeedback(Component.translatable("client-tools.ctimer.help.stop_example"));
            }
            case "stop-all" -> {
                source.sendFeedback(Component.translatable("client-tools.ctimer.help.stop_all_detail"));
            }
            case "list" -> {
                source.sendFeedback(Component.translatable("client-tools.ctimer.help.list_detail"));
            }
            default -> source.sendFeedback(Component.translatable("client-tools.ctimer.help.unknown_subcommand", subcommand));
        }
        return 1;
    }

    // --- Command executors ---

    private static int startTimer(FabricClientCommandSource source, String countStr, String durationStr, String command) {
        int times;
        if ("infinite".equalsIgnoreCase(countStr)) {
            times = -1;
        } else {
            try {
                times = Integer.parseInt(countStr);
                if (times <= 0) {
                    source.sendFeedback(Component.translatable("client-tools.ctimer.count_invalid"));
                    return 0;
                }
            } catch (NumberFormatException e) {
                source.sendFeedback(Component.translatable("client-tools.ctimer.count_parse_error", countStr));
                return 0;
            }
        }

        int intervalTicks = parseDuration(durationStr);
        if (intervalTicks <= 0) {
            source.sendFeedback(Component.translatable("client-tools.ctimer.duration_invalid", durationStr));
            return 0;
        }

        TimerInstance timer = TimerManager.addTimer(times, intervalTicks, command);

        String timesDesc = times == -1 ? "infinite" : String.valueOf(times);
        source.sendFeedback(Component.translatable(
            "client-tools.ctimer.started",
            timer.getId(), command, formatDuration(intervalTicks), timesDesc
        ));
        return 1;
    }

    private static int stopAll(FabricClientCommandSource source) {
        int removed = TimerManager.stopAll();
        if (removed == 0) {
            source.sendFeedback(Component.translatable("client-tools.ctimer.no_timers_to_stop"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ctimer.stopped_all", removed));
        }
        return removed;
    }

    private static int stopPattern(FabricClientCommandSource source, String pattern) {
        int removed = TimerManager.stop(pattern);
        if (removed == 0) {
            source.sendFeedback(Component.translatable("client-tools.ctimer.no_matching_timers", pattern));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ctimer.stopped_matching", removed, pattern));
        }
        return removed;
    }

    private static int listTimers(FabricClientCommandSource source) {
        List<TimerInstance> activeTimers = TimerManager.getTimers();

        if (activeTimers.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.ctimer.no_active_timers"));
            return 0;
        }

        source.sendFeedback(Component.translatable("client-tools.ctimer.list_header", activeTimers.size()));
        for (TimerInstance timer : activeTimers) {
            String timesDesc = timer.isInfinite()
                ? "infinite"
                : timer.getRemainingTimes() + "/" + timer.getTotalTimes() + " remaining";
            source.sendFeedback(Component.translatable(
                "client-tools.ctimer.list_entry",
                timer.getId(), timer.getCommand(), formatDuration(timer.getIntervalTicks()), timesDesc
            ));
        }
        return activeTimers.size();
    }

    // --- Duration parsing / formatting utilities ---

    /**
     * Parse a duration string into Minecraft ticks (20 ticks per second).
     * Supported suffixes: ms (milliseconds), s (seconds), m (minutes), h (hours), t (ticks).
     * A plain number without suffix is interpreted as ticks.
     *
     * @param input the duration string (e.g. "1s", "5m", "500ms", "20t", "30")
     * @return the tick count, or -1 if parsing fails
     */
    public static int parseDuration(String input) {
        input = input.trim().toLowerCase();
        if (input.isEmpty()) return -1;

        double multiplier; // ticks per unit
        String numberPart;

        if (input.endsWith("ms")) {
            numberPart = input.substring(0, input.length() - 2);
            try {
                double ms = Double.parseDouble(numberPart);
                return Math.max(1, (int) Math.round(ms / 50.0));
            } catch (NumberFormatException e) {
                return -1;
            }
        } else if (input.endsWith("s")) {
            multiplier = 20.0; // 20 ticks per second
            numberPart = input.substring(0, input.length() - 1);
        } else if (input.endsWith("m")) {
            multiplier = 60.0 * 20.0; // 1200 ticks per minute
            numberPart = input.substring(0, input.length() - 1);
        } else if (input.endsWith("h")) {
            multiplier = 3600.0 * 20.0; // 72000 ticks per hour
            numberPart = input.substring(0, input.length() - 1);
        } else if (input.endsWith("t")) {
            multiplier = 1.0; // raw ticks
            numberPart = input.substring(0, input.length() - 1);
        } else {
            multiplier = 1.0; // plain number = ticks
            numberPart = input;
        }

        try {
            double value = Double.parseDouble(numberPart);
            return Math.max(1, (int) Math.round(value * multiplier));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Format a tick count into a human-readable duration string.
     *
     * @param ticks the number of ticks
     * @return a formatted string like "1h 30m", "45s", "10t", or "500ms"
     */
    public static String formatDuration(int ticks) {
        if (ticks <= 0) return "0t";

        if (ticks < 20) {
            return ticks + "t";
        }

        long totalSeconds = ticks / 20;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("h");
            if (minutes > 0 || seconds > 0) sb.append(" ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m");
            if (seconds > 0) sb.append(" ");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append("s");
        }
        return sb.toString();
    }
}
