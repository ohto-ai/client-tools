package indi.ohtoai.tool.client_tools.client.riptide;

/**
 * Runtime toggle state for the /criptide Riptide trident flight override.
 *
 * <p>When enabled, the {@code isInWaterOrRain()} check on the local player
 * always returns {@code true}, allowing Riptide tridents to work anywhere —
 * including deserts, caves, and under roofs.
 *
 * <p>No persistence — resets to off each session. Follows the simple static
 * getter/setter pattern used by {@code BowTrajectoryState}.
 */
public class RiptideState {

    private static boolean enabled = false;

    private RiptideState() {}

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
