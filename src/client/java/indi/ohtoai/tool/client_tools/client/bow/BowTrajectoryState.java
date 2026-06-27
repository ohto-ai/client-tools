package indi.ohtoai.tool.client_tools.client.bow;

/**
 * Runtime toggle state for the /cbow arrow trajectory prediction feature.
 *
 * <p>No persistence — resets to off each session. Follows the simple static
 * getter/setter pattern used by {@code ClientToolsConfig}.
 */
public class BowTrajectoryState {

    private static boolean enabled = false;

    private BowTrajectoryState() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    /** Toggle on/off, returns the new state. */
    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }
}
