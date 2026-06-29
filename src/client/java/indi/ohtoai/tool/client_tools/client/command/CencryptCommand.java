package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import indi.ohtoai.tool.client_tools.client.p2p.P2pChatManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * {@code /cencrypt} — AES-256-GCM encrypted private chat.
 *
 * <p>Messages are encrypted and sent through {@code /msg}.
 * A Mixin intercepts incoming messages, decrypts the payload, and replaces
 * the ciphertext with the plaintext before it reaches the chat HUD.
 */
public class CencryptCommand {

    // ── Dynamic alias registration ────────────────────────────
    private static CommandDispatcher<FabricClientCommandSource> storedDispatcher;
    private static LiteralCommandNode<FabricClientCommandSource> aliasNode;
    private static String currentAlias = "";

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"key", "msg", "group", "status", "stop", "set", "alias"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    /** Online players excluding the local player — used by {@code /cencrypt msg}. */
    private static final SuggestionProvider<FabricClientCommandSource> PLAYER_SUGGESTIONS =
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

    /** Online players not already in the group — used by {@code group add}. */
    private static final SuggestionProvider<FabricClientCommandSource> GROUP_ADD_SUGGESTIONS =
        (ctx, builder) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null) {
                var members = P2pChatManager.getInstance().getGroupMembers();
                String self = client.getUser().getName();
                String remaining = builder.getRemaining().toLowerCase();
                for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                    String name = info.getProfile().getName();
                    if (name.equalsIgnoreCase(self)) continue;
                    if (members.contains(name)) continue;
                    if (name.toLowerCase().startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
            return builder.buildFuture();
        };

    /** Current group members (including offline) — used by {@code group remove}. */
    private static final SuggestionProvider<FabricClientCommandSource> GROUP_REMOVE_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String name : P2pChatManager.getInstance().getGroupMembers()) {
                if (name.toLowerCase().startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };

    /** Suggests "em" as the default alias — used by {@code alias}. */
    private static final SuggestionProvider<FabricClientCommandSource> ALIAS_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            if ("em".startsWith(remaining)) {
                builder.suggest("em");
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        storedDispatcher = dispatcher;
        dispatcher.register(
            literal("cencrypt")
                // ── key ──────────────────────────────────────
                .then(literal("key")
                    .then(argument("password", StringArgumentType.greedyString())
                        .executes(ctx -> setKey(ctx.getSource(),
                            StringArgumentType.getString(ctx, "password")))))
                // ── msg ──────────────────────────────────────
                .then(literal("msg")
                    .then(argument("player", StringArgumentType.word())
                        .suggests(PLAYER_SUGGESTIONS)
                        .then(argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> sendMsg(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"),
                                StringArgumentType.getString(ctx, "message"))))))
                // ── group ────────────────────────────────────
                .then(literal("group")
                    .then(literal("add")
                        .then(argument("player", StringArgumentType.word())
                            .suggests(GROUP_ADD_SUGGESTIONS)
                            .executes(ctx -> groupAdd(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")))))
                    .then(literal("remove")
                        .then(argument("player", StringArgumentType.word())
                            .suggests(GROUP_REMOVE_SUGGESTIONS)
                            .executes(ctx -> groupRemove(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")))))
                    .then(literal("list")
                        .executes(ctx -> groupList(ctx.getSource())))
                    .then(literal("clear")
                        .executes(ctx -> groupClear(ctx.getSource())))
                    .then(literal("send")
                        .then(argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> groupSend(ctx.getSource(),
                                StringArgumentType.getString(ctx, "message"))))))
                // ── status ───────────────────────────────────
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
                // ── stop ─────────────────────────────────────
                .then(literal("stop")
                    .executes(ctx -> stop(ctx.getSource())))
                // ── set (player-specific key) ────────────────
                .then(literal("set")
                    .then(argument("player", StringArgumentType.word())
                        .suggests(PLAYER_SUGGESTIONS)
                        .executes(ctx -> clearPlayerKey(ctx.getSource(),
                            StringArgumentType.getString(ctx, "player")))
                        .then(argument("password", StringArgumentType.greedyString())
                            .executes(ctx -> setPlayerKey(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"),
                                StringArgumentType.getString(ctx, "password"))))))
                // ── alias ────────────────────────────────────
                .then(literal("alias")
                    .executes(ctx -> clearAlias(ctx.getSource()))
                    .then(argument("alias", StringArgumentType.word())
                        .suggests(ALIAS_SUGGESTIONS)
                        .executes(ctx -> setAlias(ctx.getSource(),
                            StringArgumentType.getString(ctx, "alias")))))
                // ── help ─────────────────────────────────────
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand")))))
        );

        // Register alias command from saved config (default "em")
        String savedAlias = P2pChatManager.getInstance().getAlias();
        if (!savedAlias.isEmpty()) {
            registerAliasNode(savedAlias);
        }
    }

    // ── key ────────────────────────────────────────────────────

    private static int setKey(FabricClientCommandSource source, String password) {
        if (password.isBlank()) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.key_empty"));
            return 0;
        }
        P2pChatManager.getInstance().setPassword(password);
        source.sendFeedback(Component.translatable("client-tools.cencrypt.key_set"));
        return 1;
    }

    // ── msg ────────────────────────────────────────────────────

    private static int sendMsg(FabricClientCommandSource source, String player, String message) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        if (!mgr.isKeySet()) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.key_not_set"));
            return 0;
        }
        if (player.equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.self_msg"));
            return 0;
        }
        mgr.sendToPlayer(player, message);
        return 1;
    }

    // ── group ──────────────────────────────────────────────────

    private static int groupAdd(FabricClientCommandSource source, String player) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        if (player.equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_self"));
            return 0;
        }
        if (mgr.getGroupMembers().contains(player)) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_already_in", player));
            return 0;
        }
        mgr.addGroupMember(player);
        source.sendFeedback(Component.translatable("client-tools.cencrypt.group_added", player));
        return 1;
    }

    private static int groupRemove(FabricClientCommandSource source, String player) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        if (mgr.removeGroupMember(player)) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_removed", player));
        } else {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_not_found", player));
        }
        return 1;
    }

    private static int groupList(FabricClientCommandSource source) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        var members = mgr.getGroupMembers();
        source.sendFeedback(Component.translatable("client-tools.cencrypt.group_list_header", members.size()));
        if (members.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_list_empty"));
        } else {
            for (String m : members) {
                source.sendFeedback(Component.translatable("client-tools.cencrypt.group_list_entry", m));
            }
        }
        return 1;
    }

    private static int groupSend(FabricClientCommandSource source, String message) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        if (!mgr.isKeySet()) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.key_not_set"));
            return 0;
        }
        if (mgr.getGroupMembers().isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_empty"));
            return 0;
        }
        int sent = mgr.sendToGroup(message);
        if (sent == 0) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_all_offline"));
        } else {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_sent", sent));
        }
        return 1;
    }

    private static int groupClear(FabricClientCommandSource source) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        int count = mgr.getGroupMembers().size();
        if (count == 0) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.group_already_empty"));
            return 0;
        }
        mgr.clearGroup();
        source.sendFeedback(Component.translatable("client-tools.cencrypt.group_cleared", count));
        return 1;
    }

    // ── status ─────────────────────────────────────────────────

    private static int showStatus(FabricClientCommandSource source) {
        P2pChatManager mgr = P2pChatManager.getInstance();
        source.sendFeedback(Component.translatable("client-tools.cencrypt.status_header"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.status_key",
            mgr.isKeySet()
                ? Component.translatable("client-tools.cencrypt.status_key_set").getString()
                : Component.translatable("client-tools.cencrypt.status_key_not_set").getString()));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.status_group",
            mgr.getGroupMembers().size()));
        return 1;
    }

    // ── stop ───────────────────────────────────────────────────

    private static int stop(FabricClientCommandSource source) {
        P2pChatManager.getInstance().clearKey();
        source.sendFeedback(Component.translatable("client-tools.cencrypt.stopped"));
        return 1;
    }

    // ── help ───────────────────────────────────────────────────

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.header"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.key"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.msg"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.group"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.status"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.stop"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.set"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.alias"));
        source.sendFeedback(Component.translatable("client-tools.cencrypt.help.example"));
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "key" -> source.sendFeedback(
                Component.translatable("client-tools.cencrypt.help.key_detail"));
            case "msg" -> source.sendFeedback(
                Component.translatable("client-tools.cencrypt.help.msg_detail"));
            case "group" -> source.sendFeedback(
                Component.translatable("client-tools.cencrypt.help.group_detail"));
            case "status" -> source.sendFeedback(
                Component.translatable("client-tools.cencrypt.help.status_detail"));
            case "stop" -> source.sendFeedback(
                Component.translatable("client-tools.cencrypt.help.stop_detail"));
            case "set" -> source.sendFeedback(
                Component.translatable("client-tools.cencrypt.help.set_detail"));
            case "alias" -> source.sendFeedback(
                Component.translatable("client-tools.cencrypt.help.alias_detail"));
            default -> source.sendFeedback(
                Component.translatable("client-tools.cencrypt.help.unknown_subcommand", subcommand));
        }
        return 1;
    }

    // ── set (player-specific key) ────────────────────────────

    private static int setPlayerKey(FabricClientCommandSource source, String player, String password) {
        if (password.isBlank()) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.key_empty"));
            return 0;
        }
        P2pChatManager.getInstance().setPlayerPassword(player, password);
        source.sendFeedback(Component.translatable("client-tools.cencrypt.player_key_set", player));
        return 1;
    }

    private static int clearPlayerKey(FabricClientCommandSource source, String player) {
        P2pChatManager.getInstance().setPlayerPassword(player, "");
        source.sendFeedback(Component.translatable("client-tools.cencrypt.player_key_cleared", player));
        return 1;
    }

    // ── alias ────────────────────────────────────────────────

    private static int setAlias(FabricClientCommandSource source, String alias) {
        if (alias.isBlank()) {
            source.sendFeedback(Component.translatable("client-tools.cencrypt.alias_empty"));
            return 0;
        }
        P2pChatManager.getInstance().setAlias(alias);
        registerAliasNode(alias);
        source.sendFeedback(Component.translatable("client-tools.cencrypt.alias_set", alias));
        return 1;
    }

    private static int clearAlias(FabricClientCommandSource source) {
        unregisterAliasNode();
        P2pChatManager.getInstance().setAlias("");
        source.sendFeedback(Component.translatable("client-tools.cencrypt.alias_cleared"));
        return 1;
    }

    // ── Dynamic alias registration ───────────────────────────

    private static void registerAliasNode(String alias) {
        if (storedDispatcher == null || alias.isEmpty()) return;
        unregisterAliasNode();
        aliasNode = literal(alias)
            .then(argument("player", StringArgumentType.word())
                .suggests(PLAYER_SUGGESTIONS)
                .then(argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> sendMsg(ctx.getSource(),
                        StringArgumentType.getString(ctx, "player"),
                        StringArgumentType.getString(ctx, "message")))))
            .build();
        storedDispatcher.getRoot().addChild(aliasNode);
        currentAlias = alias;
    }

    private static void unregisterAliasNode() {
        if (storedDispatcher != null && aliasNode != null && !currentAlias.isEmpty()) {
            storedDispatcher.getRoot().getChildren().remove(currentAlias);
            aliasNode = null;
            currentAlias = "";
        }
    }
}
