package indi.ohtoai.tool.client_tools.client.sweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    // --- Setters (auto-save) ---

    public static void setPos1(BlockPos pos) { ensureLoaded(); pos1 = pos; save(); }
    public static void setPos2(BlockPos pos) { ensureLoaded(); pos2 = pos; save(); }
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
        save();
    }

    // --- Runtime only ---

    public static void setRunning(boolean r) { ensureLoaded(); running = r; }

    // --- Validation ---

    public static boolean hasPositions() {
        ensureLoaded();
        return pos1 != null && pos2 != null;
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
        } catch (IOException ignored) {}
    }
}
