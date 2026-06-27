package indi.ohtoai.tool.client_tools.client.riptide;

import indi.ohtoai.tool.client_tools.client.config.ClientToolsConfig;

/**
 * Runtime toggle state for the /criptide Riptide trident flight override.
 *
 * <p>When enabled, the {@code isInWaterOrRain()} check on the local player
 * always returns {@code true}, allowing Riptide tridents to work anywhere —
 * including deserts, caves, and under roofs.
 *
 * <p>Persisted via {@link ClientToolsConfig} to {@code config/client-tools/global.json}.
 */
public class RiptideState {

    private RiptideState() {}

    public static boolean isEnabled() {
        return ClientToolsConfig.isCriptideEnabled();
    }

    public static void setEnabled(boolean v) {
        ClientToolsConfig.setCriptideEnabled(v);
    }

    /** Toggle on/off, returns the new state. */
    public static boolean toggle() {
        boolean now = !ClientToolsConfig.isCriptideEnabled();
        ClientToolsConfig.setCriptideEnabled(now);
        return now;
    }
}
