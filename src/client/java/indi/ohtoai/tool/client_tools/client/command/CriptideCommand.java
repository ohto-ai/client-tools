package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.riptide.RiptideState;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * {@code /criptide} — overrides the water/rain check so Riptide tridents
 * can be used anywhere, including deserts, caves, and under roofs.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>(no args) — toggle on/off</li>
 *   <li>{@code on} / {@code off} — explicit enable/disable</li>
 *   <li>{@code status} — show current state, held trident info, and Riptide level</li>
 *   <li>{@code help} — help text</li>
 * </ul>
 */
public class CriptideCommand {

    private static final SuggestionProvider<FabricClientCommandSource> HELP_SUBCOMMAND_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"on", "off", "status"}) {
                if (s.toLowerCase().startsWith(remaining)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("criptide")
                .executes(ctx -> toggle(ctx.getSource()))
                .then(literal("on")
                    .executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(literal("off")
                    .executes(ctx -> setEnabled(ctx.getSource(), false)))
                .then(literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                    .then(argument("subcommand", StringArgumentType.word())
                        .suggests(HELP_SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> showHelpFor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "subcommand")))))
        );
    }

    // ── Toggle / set ────────────────────────────────────────────

    private static int toggle(FabricClientCommandSource source) {
        boolean now = RiptideState.toggle();
        source.sendFeedback(Component.translatable(now
            ? "client-tools.criptide.enabled"
            : "client-tools.criptide.disabled"));
        return 1;
    }

    private static int setEnabled(FabricClientCommandSource source, boolean enable) {
        if (RiptideState.isEnabled() == enable) {
            source.sendFeedback(Component.translatable(enable
                ? "client-tools.criptide.already_on"
                : "client-tools.criptide.already_off"));
        } else {
            RiptideState.setEnabled(enable);
            source.sendFeedback(Component.translatable(enable
                ? "client-tools.criptide.enabled"
                : "client-tools.criptide.disabled"));
        }
        return 1;
    }

    // ── Status ──────────────────────────────────────────────────

    private static int showStatus(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();

        source.sendFeedback(Component.translatable("client-tools.criptide.status_header"));

        // Override state
        String stateStr = RiptideState.isEnabled() ? "§aON" : "§7OFF";
        source.sendFeedback(Component.translatable("client-tools.criptide.status_state", stateStr));

        if (client.player == null) {
            return 1;
        }

        // Find held trident
        ItemStack mainHand = client.player.getMainHandItem();
        ItemStack offHand  = client.player.getOffhandItem();

        ItemStack tridentItem = ItemStack.EMPTY;
        if (mainHand.getItem() instanceof TridentItem) {
            tridentItem = mainHand;
        } else if (offHand.getItem() instanceof TridentItem) {
            tridentItem = offHand;
        }

        if (tridentItem.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.criptide.status_no_trident"));
            return 1;
        }

        // Item name
        String itemName = tridentItem.getHoverName().getString();
        source.sendFeedback(Component.translatable("client-tools.criptide.status_trident_held", itemName));

        // Riptide enchantment level
        int riptideLevel = getRiptideLevel(tridentItem, client);
        if (riptideLevel > 0) {
            source.sendFeedback(Component.translatable("client-tools.criptide.status_riptide_level", riptideLevel));
        } else {
            source.sendFeedback(Component.translatable("client-tools.criptide.status_no_riptide"));
        }

        return 1;
    }

    // ── Help ────────────────────────────────────────────────────

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.criptide.help.header"));
        source.sendFeedback(Component.translatable("client-tools.criptide.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.criptide.help.root"));
        source.sendFeedback(Component.translatable("client-tools.criptide.help.on"));
        source.sendFeedback(Component.translatable("client-tools.criptide.help.off"));
        source.sendFeedback(Component.translatable("client-tools.criptide.help.status"));
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "on" -> source.sendFeedback(
                Component.translatable("client-tools.criptide.help.on_detail"));
            case "off" -> source.sendFeedback(
                Component.translatable("client-tools.criptide.help.off_detail"));
            case "status" -> source.sendFeedback(
                Component.translatable("client-tools.criptide.help.status_detail"));
            default -> source.sendFeedback(
                Component.translatable("client-tools.criptide.help.unknown_subcommand", subcommand));
        }
        return 1;
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static int getRiptideLevel(ItemStack stack, Minecraft client) {
        if (client.level == null) return 0;
        try {
            return EnchantmentHelper.getItemEnchantmentLevel(
                client.level.holderLookup(Registries.ENCHANTMENT).getOrThrow(Enchantments.RIPTIDE),
                stack);
        } catch (Exception ignored) {
            return 0;
        }
    }
}
