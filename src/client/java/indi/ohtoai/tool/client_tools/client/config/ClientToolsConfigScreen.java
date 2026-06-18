package indi.ohtoai.tool.client_tools.client.config;

import indi.ohtoai.tool.client_tools.client.command.CflyCommand;
import indi.ohtoai.tool.client_tools.client.craft.CcraftState;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.LitematicaIntegration;
import indi.ohtoai.tool.client_tools.client.sweep.SweepExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.SweepState;
import indi.ohtoai.tool.client_tools.client.timer.TimerManager;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Builds the Cloth Config settings screen for Client Tools.
 * <p>
 * The screen exposes the live per-world configuration values —
 * the same values that the {@code /csweep}, {@code /ccraft}, and
 * other commands read and write.  Changes take effect immediately.
 */
public final class ClientToolsConfigScreen {

    private ClientToolsConfigScreen() {}

    /**
     * Creates the config screen.
     *
     * @param parent the parent screen (the ModMenu mod list)
     * @return a new Cloth Config screen
     */
    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("title.client-tools.config"))
            .setSavingRunnable(ClientToolsConfigScreen::onSave);

        ConfigEntryBuilder eb = builder.entryBuilder();

        // ---- Flight ----
        ConfigCategory flight = builder.getOrCreateCategory(
            Component.translatable("category.client-tools.flight"));

        flight.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.flight_description"))
            .build());

        flight.addEntry(eb.startBooleanToggle(
            Component.translatable("option.client-tools.cfly.enabled"),
            getFlightEnabled())
            .setSaveConsumer(ClientToolsConfigScreen::setFlightEnabled)
            .setDefaultValue(false)
            .build());

        flight.addEntry(eb.startBooleanToggle(
            Component.translatable("option.client-tools.cfly.autojump"),
            CflyCommand.isAutoJump())
            .setSaveConsumer(CflyCommand::setAutoJump)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("tooltip.client-tools.cfly.autojump"))
            .build());

        // ---- Sweep ----
        ConfigCategory sweep = builder.getOrCreateCategory(
            Component.translatable("category.client-tools.sweep"));

        sweep.addEntry(eb.startIntSlider(
            Component.translatable("option.client-tools.sweep.radius"),
            SweepState.getRadius(), 1, 64)
            .setDefaultValue(4)
            .setTooltip(Component.translatable("tooltip.client-tools.sweep.radius"))
            .setSaveConsumer(SweepState::setRadius)
            .build());

        sweep.addEntry(eb.startDoubleField(
            Component.translatable("option.client-tools.sweep.speed"),
            SweepState.getSpeed())
            .setDefaultValue(10.0)
            .setMin(0.5)
            .setMax(100.0)
            .setTooltip(Component.translatable("tooltip.client-tools.sweep.speed"))
            .setSaveConsumer(SweepState::setSpeed)
            .build());

        if (LitematicaIntegration.isAvailable()) {
            sweep.addEntry(eb.startBooleanToggle(
                Component.translatable("option.client-tools.sweep.litematica_sync"),
                SweepState.isSyncLitematica())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("tooltip.client-tools.sweep.litematica_sync"))
                .setSaveConsumer(SweepState::setSyncLitematica)
                .build());
        } else {
            sweep.addEntry(eb.startTextDescription(
                Component.translatable("text.client-tools.config.litematica_unavailable"))
                .build());
        }

        // Display sub-category
        ConfigCategory display = builder.getOrCreateCategory(
            Component.translatable("category.client-tools.display"));

        display.addEntry(eb.startBooleanToggle(
            Component.translatable("option.client-tools.sweep.show_outline"),
            SweepState.isShowOutline())
            .setDefaultValue(false)
            .setSaveConsumer(SweepState::setShowOutline)
            .build());

        display.addEntry(eb.startBooleanToggle(
            Component.translatable("option.client-tools.sweep.show_path"),
            SweepState.isShowPath())
            .setDefaultValue(false)
            .setSaveConsumer(SweepState::setShowPath)
            .build());

        display.addEntry(eb.startBooleanToggle(
            Component.translatable("option.client-tools.sweep.show_layer"),
            SweepState.isHighlightCurrentLayer())
            .setDefaultValue(true)
            .setSaveConsumer(SweepState::setHighlightCurrentLayer)
            .build());

        display.addEntry(eb.startBooleanToggle(
            Component.translatable("option.client-tools.sweep.show_dir"),
            SweepState.isShowNearestDirection())
            .setDefaultValue(true)
            .setSaveConsumer(SweepState::setShowNearestDirection)
            .build());

        // ---- Status ----
        ConfigCategory status = builder.getOrCreateCategory(
            Component.translatable("category.client-tools.status"));

        // Sweep status
        String sweepStatus = getSweepStatusText();
        status.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.sweep_status", sweepStatus))
            .build());

        // Crafting status
        String craftStatus = getCraftStatusText();
        status.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.craft_status", craftStatus))
            .build());

        // Timer count
        int timerCount = TimerManager.getTimers().size();
        status.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.timer_count", timerCount))
            .build());

        return builder.build();
    }

    // ---- Flight helpers ----

    private static boolean getFlightEnabled() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            return client.player.getAbilities().flying;
        }
        return false;
    }

    private static void setFlightEnabled(boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (!client.player.getAbilities().mayfly) return;

        var abilities = client.player.getAbilities();
        if (abilities.flying == enabled) return; // already in desired state

        abilities.flying = enabled;
        if (client.player.connection != null) {
            client.player.connection.send(
                new net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket(abilities));
        }
    }

    // ---- Status helpers ----

    private static String getSweepStatusText() {
        SweepExecutor executor = SweepExecutor.getInstance();
        if (executor.isRunning()) {
            return Component.translatable("text.client-tools.config.status_running",
                executor.getCurrentStationIndex() + 1, executor.getTotalStations()).getString();
        } else if (executor.isPaused() || SweepState.isPaused()) {
            int idx = executor.isPaused()
                ? executor.getCurrentStationIndex() + 1
                : SweepState.getSavedStationIndex() + 1;
            return Component.translatable("text.client-tools.config.status_paused", idx).getString();
        } else if (executor.isDone()) {
            return "§aDone";
        } else {
            return "§7Idle";
        }
    }

    private static String getCraftStatusText() {
        CraftingExecutor executor = CraftingExecutor.getInstance();
        if (executor.isRunning()) {
            return "§aRunning";
        } else {
            return "§7Idle";
        }
    }

    // ---- Persistence callback ----

    /**
     * Called whenever the user clicks "Save" on the Cloth Config screen.
     * Cloth Config fires each option's {@code setSaveConsumer} individually
     * before calling this global save runnable, so state is already persisted.
     */
    private static void onSave() {
        // Cloth Config already applied individual save consumers.
        // This is a hook for any additional cross-field validation or logging.
    }
}
