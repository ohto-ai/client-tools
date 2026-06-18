package indi.ohtoai.tool.client_tools.client.config;

import indi.ohtoai.tool.client_tools.client.command.CflyCommand;
import indi.ohtoai.tool.client_tools.client.craft.CcraftState;
import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.LitematicaIntegration;
import indi.ohtoai.tool.client_tools.client.sweep.SweepExecutor;
import indi.ohtoai.tool.client_tools.client.sweep.SweepState;
import indi.ohtoai.tool.client_tools.client.timer.TimerInstance;
import indi.ohtoai.tool.client_tools.client.timer.TimerManager;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.List;

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

        // ---- Craft ----
        ConfigCategory craft = builder.getOrCreateCategory(
            Component.translatable("category.client-tools.craft"));

        craft.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.craft_description"))
            .build());

        craft.addEntry(eb.startStrField(
            Component.translatable("option.client-tools.ccraft.product"),
            getCraftItemId(CcraftState.getProductItem()))
            .setDefaultValue("")
            .setTooltip(Component.translatable("tooltip.client-tools.ccraft.product"))
            .setSaveConsumer(v -> setCraftItem(v, true))
            .build());

        craft.addEntry(eb.startStrField(
            Component.translatable("option.client-tools.ccraft.source"),
            getCraftItemId(CcraftState.getSourceItem()))
            .setDefaultValue("")
            .setTooltip(Component.translatable("tooltip.client-tools.ccraft.source"))
            .setSaveConsumer(v -> setCraftItem(v, false))
            .build());

        // Station position
        craft.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.station_label"))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.station_x"),
            getPosCoord(CcraftState.getStationPos(), 'x'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'x', true, false, false))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.station_y"),
            getPosCoord(CcraftState.getStationPos(), 'y'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'y', true, false, false))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.station_z"),
            getPosCoord(CcraftState.getStationPos(), 'z'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'z', true, false, false))
            .build());

        // Input position
        craft.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.input_label"))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.input_x"),
            getPosCoord(CcraftState.getInputPos(), 'x'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'x', false, true, false))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.input_y"),
            getPosCoord(CcraftState.getInputPos(), 'y'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'y', false, true, false))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.input_z"),
            getPosCoord(CcraftState.getInputPos(), 'z'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'z', false, true, false))
            .build());

        // Output position
        craft.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.output_label"))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.output_x"),
            getPosCoord(CcraftState.getOutputPos(), 'x'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'x', false, false, true))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.output_y"),
            getPosCoord(CcraftState.getOutputPos(), 'y'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'y', false, false, true))
            .build());
        craft.addEntry(eb.startIntField(
            Component.translatable("option.client-tools.ccraft.output_z"),
            getPosCoord(CcraftState.getOutputPos(), 'z'))
            .setDefaultValue(0)
            .setSaveConsumer(v -> setPosCoord(v, 'z', false, false, true))
            .build());

        // Repeat count
        craft.addEntry(eb.startIntSlider(
            Component.translatable("option.client-tools.ccraft.count"),
            CcraftState.getRepeatCount(), -1, 9999)
            .setDefaultValue(1)
            .setTooltip(Component.translatable("tooltip.client-tools.ccraft.count"))
            .setSaveConsumer(CcraftState::setRepeatCount)
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

        sweep.addEntry(eb.startBooleanToggle(
            Component.translatable("option.client-tools.sweep.autospeed"),
            SweepState.isAutoSpeed())
            .setDefaultValue(false)
            .setTooltip(Component.translatable("tooltip.client-tools.sweep.autospeed"))
            .setSaveConsumer(SweepState::setAutoSpeed)
            .build());

        sweep.addEntry(eb.startDoubleField(
            Component.translatable("option.client-tools.sweep.maxspeed"),
            SweepState.getMaxSpeed())
            .setDefaultValue(30.0)
            .setMin(0.5)
            .setMax(100.0)
            .setTooltip(Component.translatable("tooltip.client-tools.sweep.maxspeed"))
            .setSaveConsumer(SweepState::setMaxSpeed)
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

        // ---- Timer ----
        ConfigCategory timer = builder.getOrCreateCategory(
            Component.translatable("category.client-tools.timer"));

        int timerCount = TimerManager.getActiveCount();
        timer.addEntry(eb.startTextDescription(
            Component.translatable("text.client-tools.config.timer_count", timerCount))
            .build());

        List<TimerInstance> activeTimers = TimerManager.getTimers();
        if (activeTimers.isEmpty()) {
            timer.addEntry(eb.startTextDescription(
                Component.translatable("text.client-tools.config.timer_empty"))
                .build());
        } else {
            for (TimerInstance t : activeTimers) {
                String timesDesc = t.isInfinite()
                    ? Component.translatable("text.client-tools.config.timer_infinite").getString()
                    : t.getRemainingTimes() + "/" + t.getTotalTimes();
                // Parse the interval using CtimerCommand's formatter — reuse format logic inline
                String intervalStr = formatTimerInterval(t.getIntervalTicks());
                timer.addEntry(eb.startTextDescription(
                    Component.translatable("text.client-tools.config.timer_entry",
                        t.getId(), t.getCommand(), intervalStr, timesDesc))
                    .build());
            }
        }

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

    // ---- Craft helpers ----

    /** Convert an Item to its registry ID string, or empty string if null. */
    private static String getCraftItemId(Item item) {
        if (item == null) return "";
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id != null ? id.toString() : "";
    }

    /** Parse a registry ID string and set it as product (isProduct=true) or source (isProduct=false). */
    private static void setCraftItem(String value, boolean isProduct) {
        value = value.trim();
        if (value.isEmpty()) {
            if (isProduct) {
                CcraftState.clearProductItem();
            } else {
                CcraftState.clearSourceItem();
            }
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) return; // invalid ID, ignore
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null) return; // unknown item, ignore
        if (isProduct) {
            CcraftState.setProductItem(item);
        } else {
            CcraftState.setSourceItem(item);
        }
    }

    /** Get a single coordinate from a BlockPos, or 0 if pos is null. */
    private static int getPosCoord(BlockPos pos, char axis) {
        if (pos == null) return 0;
        return switch (axis) {
            case 'x' -> pos.getX();
            case 'y' -> pos.getY();
            case 'z' -> pos.getZ();
            default -> 0;
        };
    }

    /**
     * Set a single coordinate of a BlockPos, reading the other two from the current value.
     * @param isStation true = station pos, false = not station
     * @param isInput   true = input pos
     * @param isOutput  true = output pos
     */
    private static void setPosCoord(int value, char axis, boolean isStation, boolean isInput, boolean isOutput) {
        BlockPos current;
        if (isStation) {
            current = CcraftState.getStationPos();
        } else if (isInput) {
            current = CcraftState.getInputPos();
        } else {
            current = CcraftState.getOutputPos();
        }

        int x = current != null ? current.getX() : 0;
        int y = current != null ? current.getY() : 0;
        int z = current != null ? current.getZ() : 0;

        switch (axis) {
            case 'x' -> x = value;
            case 'y' -> y = value;
            case 'z' -> z = value;
        }

        BlockPos newPos = new BlockPos(x, y, z);
        if (isStation) {
            CcraftState.setStationPos(newPos);
        } else if (isInput) {
            CcraftState.setInputPos(newPos);
        } else {
            CcraftState.setOutputPos(newPos);
        }
    }

    // ---- Timer format helper ----

    /** Format tick count to a readable duration string (reuses same logic as CtimerCommand). */
    private static String formatTimerInterval(int ticks) {
        if (ticks <= 0) return "0t";
        if (ticks < 20) return ticks + "t";
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
        } else if (executor.isDone()) {
            return "§aDone";
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
