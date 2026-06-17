package indi.ohtoai.tool.client_tools.client.sweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stores the current /csweep configuration.
 * State is persisted per-world/server.
 *
 * <p>Files are stored under {@code config/client-tools/sweep/<world-id>.json}.
 */
public class SweepState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BASE_DIR = FabricLoader.getInstance().getConfigDir()
        .resolve("client-tools").resolve("sweep");

    private static BlockPos pos1;
    private static BlockPos pos2;
    private static int radius = 4;
    private static double speed = 10.0;
    private static boolean showOutline = false;
    private static boolean showPath = false;
    private static boolean running = false;
    private static int savedStationIndex = -1; // -1 = no paused state to resume
    private static boolean paused = false;
    private static boolean unfinished = false;
    private static boolean syncLitematica = true;
    private static boolean loaded = false;
    private static String currentWorldId = "";

    private SweepState() {}

    @SuppressWarnings("unused")
    private static class StateData {
        Pos pos1;
        Pos pos2;
        int radius = 4;
        double speed = 10.0;
        boolean showOutline = false;
        boolean showPath = false;
        int savedStationIndex = -1;
        boolean paused = false;
        boolean unfinished = false;
        Boolean syncLitematica; // boxed so missing (old config) defaults to true
    }

    @SuppressWarnings("unused")
    private static class Pos {
        int x, y, z;
        Pos() {}
        Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    // --- World-aware config path ---

    private static String getCurrentWorldId() {
        Minecraft client = Minecraft.getInstance();
        if (client.getCurrentServer() != null) {
            return "server_" + sanitize(client.getCurrentServer().ip);
        }
        if (client.getSingleplayerServer() != null) {
            try {
                String name = client.getSingleplayerServer().getWorldData().getLevelName();
                return "world_" + sanitize(name);
            } catch (Exception ignored) {}
        }
        return "default";
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static Path getConfigPath() {
        return BASE_DIR.resolve(getCurrentWorldId() + ".json");
    }

    // --- Init ---

    private static void ensureLoaded() {
        String worldId = getCurrentWorldId();
        if (!loaded || !worldId.equals(currentWorldId)) {
            currentWorldId = worldId;
            pos1 = null;
            pos2 = null;
            radius = 4;
            speed = 10.0;
            showOutline = false;
            showPath = false;
            running = false;
            savedStationIndex = -1;
            paused = false;
            unfinished = false;
            syncLitematica = true;
            invalidateCache();
            load();
            loaded = true;
        }
    }

    // --- Getters ---

    public static BlockPos getPos1() { ensureLoaded(); return pos1; }
    public static BlockPos getPos2() { ensureLoaded(); return pos2; }
    public static int getRadius() { ensureLoaded(); return radius; }
    public static double getSpeed() { ensureLoaded(); return speed; }
    public static boolean isShowOutline() { ensureLoaded(); return showOutline; }
    public static boolean isShowPath() { ensureLoaded(); return showPath; }
    public static boolean isRunning() { ensureLoaded(); return running; }
    public static int getSavedStationIndex() { ensureLoaded(); return savedStationIndex; }
    public static boolean isPaused() { ensureLoaded(); return paused; }
    public static boolean isUnfinished() { ensureLoaded(); return unfinished; }
    public static boolean isSyncLitematica() { ensureLoaded(); return syncLitematica; }

    /**
     * Enables or disables Litematica area selection synchronization.
     * When enabled and Litematica is available, the sweep area is read from
     * Litematica's current selection instead of manual pos1/pos2.
     */
    public static void setSyncLitematica(boolean v) {
        ensureLoaded();
        if (syncLitematica != v) {
            syncLitematica = v;
            invalidateCache();
            save();
        }
    }

    // --- Sub-region resolution ---

    /** No-op — kept for API compatibility. Resolution always fetches fresh data. */
    private static void invalidateCache() {}

    /**
     * No-op — kept for API compatibility. Resolution always fetches fresh data.
     * @deprecated No longer needed; Litematica changes are picked up automatically.
     */
    @SuppressWarnings("unused")
    public static void refreshSubRegions() { ensureLoaded(); }

    /**
     * Returns the effective sub-region list, always fresh from Litematica
     * (if sync is on) or manual pos1/pos2.
     *
     * @return list of sub-region boxes, empty if no area is defined
     */
    public static List<LitematicaIntegration.SubRegionBox> resolveSubRegions() {
        ensureLoaded();

        if (syncLitematica && LitematicaIntegration.isAvailable()) {
            var result = LitematicaIntegration.getSubRegions();
            if (!result.isEmpty()) return result;
        }

        if (pos1 != null && pos2 != null) {
            String name = pos1.getX() + "," + pos1.getY() + "," + pos1.getZ()
                + " - " + pos2.getX() + "," + pos2.getY() + "," + pos2.getZ();
            return List.of(new LitematicaIntegration.SubRegionBox(name, pos1, pos2));
        }

        return List.of();
    }

    // --- Setters (auto-save) ---

    /** Sets pos1 and auto-disables Litematica sync if it was on. */
    public static void setPos1(BlockPos pos) {
        ensureLoaded();
        if (syncLitematica) {
            syncLitematica = false;
            invalidateCache();
        }
        pos1 = pos;
        save();
    }

    /** Sets pos2 and auto-disables Litematica sync if it was on. */
    public static void setPos2(BlockPos pos) {
        ensureLoaded();
        if (syncLitematica) {
            syncLitematica = false;
            invalidateCache();
        }
        pos2 = pos;
        save();
    }
    public static void setRadius(int r) { ensureLoaded(); radius = Math.max(1, Math.min(64, r)); save(); }
    public static void setSpeed(double s) { ensureLoaded(); speed = Math.max(0.5, Math.min(100.0, s)); save(); }
    public static void setShowOutline(boolean v) { ensureLoaded(); showOutline = v; save(); }
    public static void setShowPath(boolean v) { ensureLoaded(); showPath = v; save(); }

    // --- Pause state (persisted) ---

    public static void savePauseStationIndex(int stationIndex) {
        ensureLoaded();
        savedStationIndex = stationIndex;
        paused = true;
        running = false;
        save();
    }

    public static void clearPauseState() {
        ensureLoaded();
        savedStationIndex = -1;
        paused = false;
        unfinished = false;
        save();
    }

    /**
     * Marks the sweep as unfinished (disconnected mid-sweep).
     * Called by the disconnect handler so the player is reminded on rejoin.
     */
    public static void markUnfinished(int stationIndex) {
        ensureLoaded();
        savedStationIndex = stationIndex;
        paused = true;
        unfinished = true;
        running = false;
        save();
    }

    public static void clearUnfinished() {
        ensureLoaded();
        unfinished = false;
        save();
    }

    // --- Runtime only ---

    public static void setRunning(boolean r) { ensureLoaded(); running = r; }

    // --- Validation ---

    public static boolean hasPositions() {
        ensureLoaded();
        return !resolveSubRegions().isEmpty();
    }

    // --- Normalized bounds ---

    public static int getMinX() {
        ensureLoaded();
        return (pos1 != null && pos2 != null) ? Math.min(pos1.getX(), pos2.getX()) : 0;
    }
    public static int getMaxX() {
        ensureLoaded();
        return (pos1 != null && pos2 != null) ? Math.max(pos1.getX(), pos2.getX()) : 0;
    }
    public static int getMinY() {
        ensureLoaded();
        return (pos1 != null && pos2 != null) ? Math.min(pos1.getY(), pos2.getY()) : 0;
    }
    public static int getMaxY() {
        ensureLoaded();
        return (pos1 != null && pos2 != null) ? Math.max(pos1.getY(), pos2.getY()) : 0;
    }
    public static int getMinZ() {
        ensureLoaded();
        return (pos1 != null && pos2 != null) ? Math.min(pos1.getZ(), pos2.getZ()) : 0;
    }
    public static int getMaxZ() {
        ensureLoaded();
        return (pos1 != null && pos2 != null) ? Math.max(pos1.getZ(), pos2.getZ()) : 0;
    }

    public static long getVolume() {
        ensureLoaded();
        if (pos1 == null || pos2 == null) return 0;
        long dx = (long) getMaxX() - getMinX() + 1;
        long dy = (long) getMaxY() - getMinY() + 1;
        long dz = (long) getMaxZ() - getMinZ() + 1;
        return dx * dy * dz;
    }

    // --- Clear all ---

    public static void clear() {
        ensureLoaded();
        pos1 = null;
        pos2 = null;
        radius = 4;
        speed = 10.0;
        showOutline = false;
        showPath = false;
        running = false;
        savedStationIndex = -1;
        paused = false;
        unfinished = false;
        syncLitematica = true;
        invalidateCache();
        save();
    }

    // --- Persistence ---

    private static synchronized void save() {
        Path path = getConfigPath();
        StateData data = new StateData();
        if (pos1 != null) data.pos1 = new Pos(pos1.getX(), pos1.getY(), pos1.getZ());
        if (pos2 != null) data.pos2 = new Pos(pos2.getX(), pos2.getY(), pos2.getZ());
        data.radius = radius;
        data.speed = speed;
        data.showOutline = showOutline;
        data.showPath = showPath;
        data.savedStationIndex = savedStationIndex;
        data.paused = paused;
        data.unfinished = unfinished;
        data.syncLitematica = syncLitematica;

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data));
        } catch (IOException ignored) {}
    }

    private static synchronized void load() {
        Path path = getConfigPath();
        if (!Files.exists(path)) return;
        try {
            String json = Files.readString(path);
            StateData data = GSON.fromJson(json, StateData.class);
            if (data == null) return;
            if (data.pos1 != null) pos1 = new BlockPos(data.pos1.x, data.pos1.y, data.pos1.z);
            if (data.pos2 != null) pos2 = new BlockPos(data.pos2.x, data.pos2.y, data.pos2.z);
            radius = data.radius;
            speed = data.speed;
            showOutline = data.showOutline;
            showPath = data.showPath;
            savedStationIndex = data.savedStationIndex;
            paused = data.paused;
            unfinished = data.unfinished;
            syncLitematica = data.syncLitematica != null ? data.syncLitematica : true;
        } catch (IOException ignored) {}
    }
}
