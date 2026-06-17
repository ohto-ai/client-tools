package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.sequence.CsequenceState;
import indi.ohtoai.tool.client_tools.client.sequence.SequenceExecutor;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;

import java.nio.file.Path;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Registers the {@code /csequence} command for creating, editing, and executing
 * .mcfunction sequence files. File paths are shown as clickable chat messages
 * (click-to-copy) so users can navigate to them manually in their file manager.
 *
 * <pre>
 * /csequence
 *   new &lt;name&gt;              — create empty .mcfunction and show its path
 *   edit &lt;name&gt;             — show existing .mcfunction path
 *   run &lt;name&gt; [delay] [loop] — execute sequence
 *   stop                     — stop running sequence
 *   list                     — list all saved sequences
 *   delete &lt;name&gt;           — delete a sequence file
 *   status                   — show current execution progress
 *   folder                   — show sequences folder path
 * </pre>
 */
public class CsequenceCommand {

    // --- Suggestion provider: list saved sequence names ---

    private static final SuggestionProvider<FabricClientCommandSource> SEQUENCE_NAME_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String name : CsequenceState.listSequences()) {
                if (name.toLowerCase().startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };

    // --- Suggestion provider: duration values ---

    private static final SuggestionProvider<FabricClientCommandSource> DURATION_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"1t", "5t", "10t", "200ms", "500ms", "1s", "2s", "5s"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    // --- Suggestion provider: loop keyword ---

    private static final SuggestionProvider<FabricClientCommandSource> LOOP_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            if ("loop".startsWith(remaining)) {
                builder.suggest("loop");
            }
            return builder.buildFuture();
        };

    // --- Registration ---

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("csequence")
                // /csequence new <name>
                .then(literal("new")
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> newSequence(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))))
                // /csequence edit <name>
                .then(literal("edit")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(SEQUENCE_NAME_SUGGESTIONS)
                        .executes(ctx -> editSequence(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))))
                // /csequence run <name> [delay] [loop]
                .then(literal("run")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(SEQUENCE_NAME_SUGGESTIONS)
                        .executes(ctx -> runSequence(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"), "1t", false))
                        .then(argument("delay", StringArgumentType.word())
                            .suggests(DURATION_SUGGESTIONS)
                            .executes(ctx -> runSequence(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "delay"), false))
                            .then(argument("loop", StringArgumentType.word())
                                .suggests(LOOP_SUGGESTIONS)
                                .executes(ctx -> runSequence(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "delay"),
                                    "loop".equalsIgnoreCase(StringArgumentType.getString(ctx, "loop"))))))))
                // /csequence stop
                .then(literal("stop")
                    .executes(ctx -> stopSequence(ctx.getSource())))
                // /csequence list
                .then(literal("list")
                    .executes(ctx -> listSequences(ctx.getSource())))
                // /csequence delete <name>
                .then(literal("delete")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(SEQUENCE_NAME_SUGGESTIONS)
                        .executes(ctx -> deleteSequence(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))))
                // /csequence status
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
                // /csequence folder
                .then(literal("folder")
                    .executes(ctx -> openFolder(ctx.getSource())))
        );
    }

    // ==================== new ====================

    private static int newSequence(FabricClientCommandSource source, String name) {
        Path file = CsequenceState.createEmpty(name);
        if (file == null) {
            source.sendFeedback(Component.translatable("client-tools.csequence.create_failed", name));
            return 0;
        }

        source.sendFeedback(Component.translatable("client-tools.csequence.created", name));
        showFilePath(source, file, "client-tools.csequence.file_location");
        return 1;
    }

    // ==================== edit ====================

    private static int editSequence(FabricClientCommandSource source, String name) {
        Path file = CsequenceState.getSequenceFile(name);

        // If file doesn't exist, create it first
        if (!file.toFile().exists()) {
            Path created = CsequenceState.createEmpty(name);
            if (created == null) {
                source.sendFeedback(Component.translatable("client-tools.csequence.create_failed", name));
                return 0;
            }
            source.sendFeedback(Component.translatable("client-tools.csequence.created", name));
            file = created;
        }

        source.sendFeedback(Component.translatable("client-tools.csequence.editing", name));
        showFilePath(source, file, "client-tools.csequence.file_location");
        return 1;
    }

    // ==================== run ====================

    private static int runSequence(FabricClientCommandSource source, String name, String delayStr, boolean loop) {
        SequenceExecutor executor = SequenceExecutor.getInstance();

        if (executor.isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.csequence.already_running"));
            return 0;
        }

        if (CraftingExecutor.getInstance().isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.csequence.crafting_running"));
            return 0;
        }

        // Parse delay
        int delayTicks = CtimerCommand.parseDuration(delayStr);
        if (delayTicks <= 0) {
            source.sendFeedback(Component.translatable("client-tools.csequence.delay_invalid", delayStr));
            return 0;
        }

        // Start execution
        executor.reset();
        boolean started = executor.start(name, delayTicks, loop);
        if (!started) {
            source.sendFeedback(Component.translatable("client-tools.csequence.error", executor.getErrorMessage()));
            return 0;
        }

        String delayFormatted = CtimerCommand.formatDuration(delayTicks);
        int cmdCount = executor.getTotalCommands();
        if (loop) {
            source.sendFeedback(Component.translatable(
                "client-tools.csequence.run_loop", name, cmdCount, delayFormatted));
        } else {
            source.sendFeedback(Component.translatable(
                "client-tools.csequence.run", name, cmdCount, delayFormatted));
        }
        return 1;
    }

    // ==================== stop ====================

    private static int stopSequence(FabricClientCommandSource source) {
        SequenceExecutor executor = SequenceExecutor.getInstance();

        if (!executor.isRunning()) {
            source.sendFeedback(Component.translatable("client-tools.csequence.not_running"));
            return 0;
        }

        executor.stop();
        source.sendFeedback(Component.translatable(
            "client-tools.csequence.stopped",
            executor.getCurrentIndex(), executor.getTotalCommands()));
        return 1;
    }

    // ==================== list ====================

    private static int listSequences(FabricClientCommandSource source) {
        List<String> names = CsequenceState.listSequences();

        if (names.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.csequence.list_empty"));
            return 0;
        }

        source.sendFeedback(Component.translatable("client-tools.csequence.list_header", names.size()));
        for (String name : names) {
            int cmdCount = CsequenceState.getCommandCount(name);
            long size = CsequenceState.getFileSize(name);
            String sizeStr = size >= 1024 ? String.format("%.1fK", size / 1024.0) : size + "B";
            source.sendFeedback(Component.translatable(
                "client-tools.csequence.list_entry", name, cmdCount, sizeStr));
        }
        return names.size();
    }

    // ==================== delete ====================

    private static int deleteSequence(FabricClientCommandSource source, String name) {
        if (!CsequenceState.getSequenceFile(name).toFile().exists()) {
            source.sendFeedback(Component.translatable("client-tools.csequence.not_found", name));
            return 0;
        }

        boolean deleted = CsequenceState.deleteSequence(name);
        if (deleted) {
            source.sendFeedback(Component.translatable("client-tools.csequence.deleted", name));
        } else {
            source.sendFeedback(Component.translatable("client-tools.csequence.delete_failed", name));
        }
        return deleted ? 1 : 0;
    }

    // ==================== status ====================

    private static int showStatus(FabricClientCommandSource source) {
        SequenceExecutor executor = SequenceExecutor.getInstance();

        source.sendFeedback(Component.translatable("client-tools.csequence.status_header"));

        SequenceExecutor.State state = executor.getState();

        switch (state) {
            case IDLE -> source.sendFeedback(Component.translatable("client-tools.csequence.status_idle"));
            case RUNNING -> {
                String currentCmd = executor.getCommandAt(executor.getCurrentIndex());
                String preview = currentCmd != null && currentCmd.length() > 40
                    ? currentCmd.substring(0, 40) + "..." : currentCmd;
                source.sendFeedback(Component.translatable("client-tools.csequence.status_running",
                    executor.getSequenceName(),
                    executor.getCurrentIndex() + 1,
                    executor.getTotalCommands(),
                    CtimerCommand.formatDuration(executor.getDelayTicks()),
                    preview != null ? preview : "?"));
                if (executor.isLoop()) {
                    source.sendFeedback(Component.translatable("client-tools.csequence.status_loop"));
                }
                // Show call stack when nested
                if (executor.getDepth() > 1) {
                    List<String> stackNames = executor.getCallStackNames();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < stackNames.size(); i++) {
                        if (i > 0) sb.append(" §8→ ");
                        if (i == stackNames.size() - 1) {
                            sb.append("§e").append(stackNames.get(i));
                        } else {
                            sb.append("§7").append(stackNames.get(i));
                        }
                    }
                    source.sendFeedback(Component.translatable(
                        "client-tools.csequence.status_depth", executor.getDepth(), sb.toString()));
                }
            }
            case DONE -> source.sendFeedback(Component.translatable("client-tools.csequence.status_done",
                executor.getSequenceName(), executor.getTotalCommands()));
            case STOPPED -> source.sendFeedback(Component.translatable("client-tools.csequence.status_stopped",
                executor.getSequenceName(), executor.getCurrentIndex(), executor.getTotalCommands()));
            case ERROR -> source.sendFeedback(Component.translatable("client-tools.csequence.status_error",
                executor.getErrorMessage()));
        }

        return 1;
    }

    // ==================== folder ====================

    private static int openFolder(FabricClientCommandSource source) {
        Path dir = CsequenceState.getSequencesDir();
        String pathStr = dir.toAbsolutePath().toString();
        Component pathComponent = Component.literal(pathStr)
            .withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, pathStr))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable("client-tools.csequence.click_to_copy"))));
        source.sendFeedback(Component.translatable("client-tools.csequence.folder_location", pathStr));
        source.sendFeedback(pathComponent);
        return 1;
    }

    // ==================== internal ====================

    /**
     * Send the given file's path as a clickable chat message so the user can copy
     * it and open the file manually. This avoids launching external OS programs,
     * which is required for CurseForge compliance.
     */
    private static void showFilePath(FabricClientCommandSource source, Path file, String translationKey) {
        String pathStr = file.toAbsolutePath().toString();
        source.sendFeedback(Component.translatable(translationKey, pathStr));
        source.sendFeedback(Component.literal(pathStr)
            .withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, pathStr))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable("client-tools.csequence.click_to_copy")))));
    }
}
