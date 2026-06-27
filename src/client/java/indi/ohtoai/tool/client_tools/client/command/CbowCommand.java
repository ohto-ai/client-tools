package indi.ohtoai.tool.client_tools.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indi.ohtoai.tool.client_tools.client.bow.BowTrajectoryState;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * {@code /cbow} — toggles arrow trajectory prediction.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>(no args) — toggle on/off</li>
 *   <li>{@code on} / {@code off} — explicit enable/disable</li>
 *   <li>{@code status} — show current state and weapon info</li>
 *   <li>{@code help} — help text</li>
 * </ul>
 */
public class CbowCommand {

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
            literal("cbow")
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
        boolean now = BowTrajectoryState.toggle();
        source.sendFeedback(Component.translatable(now
            ? "client-tools.cbow.enabled"
            : "client-tools.cbow.disabled"));
        return 1;
    }

    private static int setEnabled(FabricClientCommandSource source, boolean enable) {
        if (BowTrajectoryState.isEnabled() == enable) {
            source.sendFeedback(Component.translatable(enable
                ? "client-tools.cbow.already_on"
                : "client-tools.cbow.already_off"));
        } else {
            BowTrajectoryState.setEnabled(enable);
            source.sendFeedback(Component.translatable(enable
                ? "client-tools.cbow.enabled"
                : "client-tools.cbow.disabled"));
        }
        return 1;
    }

    // ── Status ──────────────────────────────────────────────────

    private static int showStatus(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();

        source.sendFeedback(Component.translatable("client-tools.cbow.status_header"));

        // Prediction state
        String stateStr = BowTrajectoryState.isEnabled() ? "§aON" : "§7OFF";
        source.sendFeedback(Component.translatable("client-tools.cbow.status_state", stateStr));

        if (client.player == null) {
            return 1;
        }

        // Find held weapon
        ItemStack mainHand = client.player.getMainHandItem();
        ItemStack offHand  = client.player.getOffhandItem();

        ItemStack bowItem = ItemStack.EMPTY;
        if (isBowOrCrossbow(mainHand)) {
            bowItem = mainHand;
        } else if (isBowOrCrossbow(offHand)) {
            bowItem = offHand;
        }

        if (bowItem.isEmpty()) {
            source.sendFeedback(Component.translatable("client-tools.cbow.status_no_bow"));
            return 1;
        }

        // Weapon name
        String itemName = bowItem.getHoverName().getString();
        source.sendFeedback(Component.translatable("client-tools.cbow.status_weapon", itemName));

        boolean isCrossbow = bowItem.getItem() instanceof CrossbowItem;

        if (isCrossbow) {
            if (!isCrossbowCharged(bowItem)) {
                source.sendFeedback(Component.translatable(
                    "client-tools.cbow.status_crossbow_not_charged"));
                return 1;
            }
            source.sendFeedback(Component.translatable("client-tools.cbow.status_speed", 3.15));
        } else {
            if (!client.player.isUsingItem()
                    || !(client.player.getUseItem().getItem() instanceof BowItem)) {
                source.sendFeedback(Component.translatable(
                    "client-tools.cbow.status_charge", 0));
            } else {
                float charge = Math.min(client.player.getTicksUsingItem(), 20) / 20.0f;
                source.sendFeedback(Component.translatable(
                    "client-tools.cbow.status_charge", charge * 100.0));
                source.sendFeedback(Component.translatable(
                    "client-tools.cbow.status_speed", charge * 3.0));
            }
        }

        // Enchantments
        if (client.level != null) {
            try {
                var lookup = client.level.holderLookup(Registries.ENCHANTMENT);
                StringBuilder enchBuilder = new StringBuilder();

                int power = EnchantmentHelper.getItemEnchantmentLevel(
                    lookup.getOrThrow(Enchantments.POWER), bowItem);
                int punch = EnchantmentHelper.getItemEnchantmentLevel(
                    lookup.getOrThrow(Enchantments.PUNCH), bowItem);
                int flame = EnchantmentHelper.getItemEnchantmentLevel(
                    lookup.getOrThrow(Enchantments.FLAME), bowItem);
                int infinity = EnchantmentHelper.getItemEnchantmentLevel(
                    lookup.getOrThrow(Enchantments.INFINITY), bowItem);
                int multishot = EnchantmentHelper.getItemEnchantmentLevel(
                    lookup.getOrThrow(Enchantments.MULTISHOT), bowItem);
                int piercing = EnchantmentHelper.getItemEnchantmentLevel(
                    lookup.getOrThrow(Enchantments.PIERCING), bowItem);
                int quickCharge = EnchantmentHelper.getItemEnchantmentLevel(
                    lookup.getOrThrow(Enchantments.QUICK_CHARGE), bowItem);

                if (power > 0)      enchBuilder.append("Power ").append(power).append(" ");
                if (punch > 0)      enchBuilder.append("Punch ").append(punch).append(" ");
                if (flame > 0)      enchBuilder.append("Flame ");
                if (infinity > 0)   enchBuilder.append("Infinity ");
                if (multishot > 0)  enchBuilder.append("§eMultishot ").append(multishot).append("§r ");
                if (piercing > 0)   enchBuilder.append("Piercing ").append(piercing).append(" ");
                if (quickCharge > 0) enchBuilder.append("QuickCharge ").append(quickCharge).append(" ");

                String enchStr = enchBuilder.toString().trim();
                if (enchStr.isEmpty()) {
                    enchStr = Component.translatable("client-tools.cbow.status_enchantments_none").getString();
                }
                source.sendFeedback(Component.translatable(
                    "client-tools.cbow.status_enchantments", "§7" + enchStr));
            } catch (Exception ignored) {
                // Enchantment lookup may fail
            }
        }

        return 1;
    }

    // ── Help ────────────────────────────────────────────────────

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("client-tools.cbow.help.header"));
        source.sendFeedback(Component.translatable("client-tools.cbow.help.overview"));
        source.sendFeedback(Component.translatable("client-tools.cbow.help.root"));
        source.sendFeedback(Component.translatable("client-tools.cbow.help.on"));
        source.sendFeedback(Component.translatable("client-tools.cbow.help.off"));
        source.sendFeedback(Component.translatable("client-tools.cbow.help.status"));
        return 1;
    }

    private static int showHelpFor(FabricClientCommandSource source, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "on" -> source.sendFeedback(
                Component.translatable("client-tools.cbow.help.on_detail"));
            case "off" -> source.sendFeedback(
                Component.translatable("client-tools.cbow.help.off_detail"));
            case "status" -> source.sendFeedback(
                Component.translatable("client-tools.cbow.help.status_detail"));
            default -> source.sendFeedback(
                Component.translatable("client-tools.cbow.help.unknown_subcommand", subcommand));
        }
        return 1;
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static boolean isBowOrCrossbow(ItemStack stack) {
        return stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem;
    }

    private static boolean isCrossbowCharged(ItemStack stack) {
        return stack.has(DataComponents.CHARGED_PROJECTILES);
    }
}
