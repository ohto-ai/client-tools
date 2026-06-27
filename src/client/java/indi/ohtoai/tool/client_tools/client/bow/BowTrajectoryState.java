package indi.ohtoai.tool.client_tools.client.bow;

import indi.ohtoai.tool.client_tools.client.config.ClientToolsConfig;

/**
 * Runtime toggle state for the /cbow arrow trajectory prediction feature.
 *
 * <p>Persisted via {@link ClientToolsConfig} to {@code config/client-tools/global.json}.
 */
public class BowTrajectoryState {

    private BowTrajectoryState() {}

    public static boolean isEnabled() {
        return ClientToolsConfig.isCbowEnabled();
    }

    public static void setEnabled(boolean v) {
        ClientToolsConfig.setCbowEnabled(v);
    }

    /** Toggle on/off, returns the new state. */
    public static boolean toggle() {
        boolean now = !ClientToolsConfig.isCbowEnabled();
        ClientToolsConfig.setCbowEnabled(now);
        return now;
    }
}
