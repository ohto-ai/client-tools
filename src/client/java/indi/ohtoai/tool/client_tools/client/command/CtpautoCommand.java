package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.config.ClientToolsConfig;
import indi.ohtoai.tool.client_tools.client.tpauto.TpAutoManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * {@code /ctpauto} — configure automatic teleport request handling.
 *
 * <p>Controls how the client responds to incoming {@code /tpa} and
 * {@code /tpahere} requests from server plugins.
 */
public final class CtpautoCommand {

    private static final SuggestionProvider<FabricClientCommandSource> MODE_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String mode : new String[]{"allow_all", "deny_all", "whitelist"}) {
                if (mode.startsWith(remaining)) {
                    builder.suggest(mode);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> ONLINE_PLAYER_SUGGESTIONS =
        (ctx, builder) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null) {
                String self = client.getUser().getName();
                String remaining = builder.getRemaining().toLowerCase();
                for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                    String name = info.getProfile().getName();
                    if (name.equalsIgnoreCase(self)) continue;
                    if (name.toLowerCase().startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> WHITELIST_REMOVE_SUGGESTIONS =
        (ctx, builder) -> {
            String type = "tpa";
            try { type = StringArgumentType.getString(ctx, "type"); } catch (IllegalArgumentException ignored) {}
            List<String> whitelist = "tpahere".equalsIgnoreCase(type)
                ? ClientToolsConfig.getTpahereWhitelist()
                : ClientToolsConfig.getTpAutoWhitelist();
            String remaining = builder.getRemaining().toLowerCase();
            for (String name : whitelist) {
                if (name.toLowerCase().startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"enable", "disable", "tpa", "tpahere", "status"}) {
                if (s.startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("ctpauto")
                // ── enable ─────────────────────────────────
                .then(literal("enable")
                    .executes(ctx -> setEnabled(ctx.getSource(), true)))
                // ── disable ────────────────────────────────
                .then(literal("disable")
                    .executes(ctx -> setEnabled(ctx.getSource(), false)))
                // ── status ──────────────────────────────────
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
                // ── tpa ─────────────────────────────────────
                .then(literal("tpa")
                    .then(literal("mode")
                        .then(argument("mode", StringArgumentType.word())
                            .suggests(MODE_SUGGESTIONS)
                            .executes(ctx -> setMode(ctx.getSource(), "tpa",
                                StringArgumentType.getString(ctx, "mode")))))
                    .then(literal("whitelist")
                        .then(literal("add")
                            .then(argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                .executes(ctx -> whitelistAdd(ctx.getSource(), "tpa",
                                    StringArgumentType.getString(ctx, "player")))))
                        .then(literal("remove")
                            .then(argument("player", StringArgumentType.word())
                                .suggests(WHITELIST_REMOVE_SUGGESTIONS)
                                .executes(ctx -> whitelistRemove(ctx.getSource(), "tpa",
                                    StringArgumentType.getString(ctx, "player")))))
                        .then(literal("list")
                            .executes(ctx -> whitelistList(ctx.getSource(), "tpa")))
                        .then(literal("clear")
                            .executes(ctx -> whitelistClear(ctx.getSource(), "tpa")))))
                // ── tpahere ─────────────────────────────────
                .then(literal("tpahere")
                    .then(literal("mode")
                        .then(argument("mode", StringArgumentType.word())
                            .suggests(MODE_SUGGESTIONS)
                            .executes(ctx -> setMode(ctx.getSource(), "tpahere",
                                StringArgumentType.getString(ctx, "mode")))))
                    .then(literal("whitelist")
                        .then(literal("add")
                            .then(argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                .executes(ctx -> whitelistAdd(ctx.getSource(), "tpahere",
                                    StringArgumentType.getString(ctx, "player")))))
                        .then(literal("remove")
                            .then(argument("player", StringArgumentType.word())
                                .suggests(WHITELIST_REMOVE_SUGGESTIONS)
                                .executes(ctx -> whitelistRemove(ctx.getSource(), "tpahere",
                                    StringArgumentType.getString(ctx, "player")))))
                        .then(literal("list")
                            .executes(ctx -> whitelistList(ctx.getSource(), "tpahere")))
                        .then(literal("clear")
                            .executes(ctx -> whitelistClear(ctx.getSource(), "tpahere")))))
                // ── help ────────────────────────────────────
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand")))))
                // ── default (no args) → status ──────────────
                .executes(ctx -> showStatus(ctx.getSource()))
        );
    }

    // ── enable / disable ────────────────────────────────────────

    private static int setEnabled(FabricClientCommandSource source, boolean enabled) {
        boolean wasEnabled = ClientToolsConfig.isTpAutoEnabled();
        if (enabled == wasEnabled) {
            source.sendFeedback(Component.translatable(
                enabled ? "client-tools.ctpauto.already_on"
                        : "client-tools.ctpauto.already_off"));
            return 0;
        }
        ClientToolsConfig.setTpAutoEnabled(enabled);
        source.sendFeedback(Component.translatable(
            enabled ? "client-tools.ctpauto.enabled"
                    : "client-tools.ctpauto.disabled"));
        return 1;
    }

    // ── mode ────────────────────────────────────────────────────

    private static int setMode(FabricClientCommandSource source, String type, String mode) {
        mode = mode.toLowerCase();
        if (!mode.equals("allow_all") && !mode.equals("deny_all") && !mode.equals("whitelist")) {
            source.sendError(Component.translatable("client-tools.ctpauto.mode_invalid", mode));
            return 0;
        }
        String label = "tpahere".equalsIgnoreCase(type) ? "TPAHere" : "TPA";
        if ("tpahere".equalsIgnoreCase(type)) {
            ClientToolsConfig.setTpahereAutoMode(mode);
        } else {
            ClientToolsConfig.setTpAutoMode(mode);
        }
        // Auto-enable when user explicitly sets a mode
        if (!ClientToolsConfig.isTpAutoEnabled()) {
            ClientToolsConfig.setTpAutoEnabled(true);
            source.sendFeedback(Component.translatable("client-tools.ctpauto.mode_set", label, mode));
            source.sendFeedback(Component.translatable("client-tools.ctpauto.enabled"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ctpauto.mode_set", label, mode));
        }
        return 1;
    }

    // ── whitelist add ───────────────────────────────────────────

    private static int whitelistAdd(FabricClientCommandSource source, String type, String player) {
        Minecraft client = Minecraft.getInstance();
        if (client.getUser() != null && client.getUser().getName().equalsIgnoreCase(player)) {
            source.sendError(Component.translatable("client-tools.ctpauto.whitelist_self"));
            return 0;
        }
        String label = "tpahere".equalsIgnoreCase(type) ? "TPAHere" : "TPA";
        List<String> whitelist = "tpahere".equalsIgnoreCase(type)
            ? ClientToolsConfig.getTpahereWhitelist()
            : ClientToolsConfig.getTpAutoWhitelist();

        if (whitelist.stream().anyMatch(n -> n.equalsIgnoreCase(player))) {
            source.sendFeedback(Component.translatable("client-tools.ctpauto.whitelist_already_added", player, label));
            return 0;
        }
        whitelist.add(player);
        if ("tpahere".equalsIgnoreCase(type)) {
            ClientToolsConfig.setTpahereWhitelist(whitelist);
        } else {
            ClientToolsConfig.setTpAutoWhitelist(whitelist);
        }
        source.sendFeedback(Component.translatable("client-tools.ctpauto.whitelist_added", player, label));
        return 1;
    }

    // ── whitelist remove ────────────────────────────────────────

    private static int whitelistRemove(FabricClientCommandSource source, String type, String player) {
        String label = "tpahere".equalsIgnoreCase(type) ? "TPAHere" : "TPA";
        List<String> whitelist = "tpahere".equalsIgnoreCase(type)
            ? ClientToolsConfig.getTpahereWhitelist()
            : ClientToolsConfig.getTpAutoWhitelist();

        boolean removed = whitelist.removeIf(n -> n.equalsIgnoreCase(player));
        if (!removed) {
            source.sendError(Component.translatable("client-tools.ctpauto.whitelist_not_found", player, label));
            return 0;
        }
        if ("tpahere".equalsIgnoreCase(type)) {
            ClientToolsConfig.setTpahereWhitelist(whitelist);
        } else {
            ClientToolsConfig.setTpAutoWhitelist(whitelist);
        }
        source.sendFeedback(Component.translatable("client-tools.ctpauto.whitelist_removed", player, label));
        return 1;
    }

    // ── whitelist list ──────────────────────────────────────────

    private static int whitelistList(FabricClientCommandSource source, String type) {
        String label = "tpahere".equalsIgnoreCase(type) ? "TPAHere" : "TPA";
        List<String> whitelist = "tpahere".equalsIgnoreCase(type)
            ? ClientToolsConfig.getTpahereWhitelist()
            : ClientToolsConfig.getTpAutoWhitelist();

        if (whitelist.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.ctpauto.whitelist_list_empty", label));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ctpauto.whitelist_list_header",
                label, whitelist.size()));
            for (String name : whitelist) {
                source.sendFeedback(Component.translatable("client-tools.ctpauto.whitelist_list_entry", name));
            }
        }
        return 1;
    }

    // ── whitelist clear ─────────────────────────────────────────

    private static int whitelistClear(FabricClientCommandSource source, String type) {
        String label = "tpahere".equalsIgnoreCase(type) ? "TPAHere" : "TPA";
        if ("tpahere".equalsIgnoreCase(type)) {
            ClientToolsConfig.setTpahereWhitelist(new java.util.ArrayList<>());
        } else {
            ClientToolsConfig.setTpAutoWhitelist(new java.util.ArrayList<>());
        }
        source.sendFeedback(Component.translatable("client-tools.ctpauto.whitelist_cleared", label));
        return 1;
    }

    // ── status ──────────────────────────────────────────────────

    private static int showStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.ctpauto.status_header"));

        boolean enabled = ClientToolsConfig.isTpAutoEnabled();
        source.sendFeedback(Component.translatable("client-tools.ctpauto.status_enabled",
            enabled ? "§aON" : "§7OFF"));

        if (!enabled) return 1;

        source.sendFeedback(Component.translatable("client-tools.ctpauto.status_mode_tpa",
            "§e" + ClientToolsConfig.getTpAutoMode()));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.status_mode_tpahere",
            "§e" + ClientToolsConfig.getTpahereAutoMode()));

        int tpaCount = ClientToolsConfig.getTpAutoWhitelist().size();
        int tpahereCount = ClientToolsConfig.getTpahereWhitelist().size();
        source.sendFeedback(Component.translatable("client-tools.ctpauto.status_whitelist_tpa", tpaCount));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.status_whitelist_tpahere", tpahereCount));

        TpAutoManager.PendingTpRequest req = TpAutoManager.getInstance().getActiveRequest();
        if (req != null) {
            source.sendFeedback(Component.translatable("client-tools.ctpauto.status_pending",
                "§e" + req.sender(), "§e" + req.type().name()));
        } else {
            source.sendFeedback(Component.translatable("client-tools.ctpauto.status_no_pending"));
        }

        return 1;
    }

    // ── help ────────────────────────────────────────────────────

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.ctpauto.help.header"));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.help.enable"));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.help.disable"));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.help.mode"));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.help.whitelist"));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.help.status"));
        source.sendFeedback(Component.translatable("client-tools.ctpauto.help.example"));
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        String key = switch (subcommand.toLowerCase()) {
            case "enable" -> "client-tools.ctpauto.help.enable_detail";
            case "disable" -> "client-tools.ctpauto.help.disable_detail";
            case "tpa" -> "client-tools.ctpauto.help.tpa_detail";
            case "tpahere" -> "client-tools.ctpauto.help.tpahere_detail";
            case "status" -> "client-tools.ctpauto.help.status_detail";
            default -> null;
        };
        if (key == null) {
            source.sendError(Component.translatable("client-tools.ctpauto.help.unknown_subcommand", subcommand));
            return 0;
        }
        source.sendFeedback(Component.translatable(key));
        return 1;
    }
}
