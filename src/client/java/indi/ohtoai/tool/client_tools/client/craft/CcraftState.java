package indi.ohtoai.tool.client_tools.client.craft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Stores /ccraft configurations with multi-station support.
 * Each station is a named profile containing source/product items,
 * station/input/output positions, and repeat count.
 *
 * <p>Files are stored under {@code config/client-tools/ccraft/<world-id>.json}.
 *
 * <p>New multi-station format:
 * <pre>{@code
 * {
 *   "activeStation": "home_base",
 *   "stations": {
 *     "home_base": { "sourceItem": ..., "productItem": ..., ... }
 *   }
 * }
 * }</pre>
 * Old flat format is auto-migrated on load.
 */
public class CcraftState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BASE_DIR = FabricLoader.getInstance().getConfigDir()
        .resolve("client-tools").resolve("ccraft");

    // --- In-memory current station state ---
    private static Item sourceItem;
    private static Item productItem;
    private static BlockPos stationPos;
    private static BlockPos inputPos;
    private static BlockPos outputPos;
    private static int repeatCount = 1;
    private static String currentStationName = null;
    private static boolean loaded = false;
    private static String currentWorldId = "";

    // --- Persistent storage ---
    private static final Map<String, StationData> stations = new LinkedHashMap<>();

    private CcraftState() {}

    // ==================== Serializable data holders ====================

    @SuppressWarnings("unused")
    private static class WorldConfig {
        String activeStation;
        Map<String, StationData> stations = new LinkedHashMap<>();
    }

    @SuppressWarnings("unused")
    public static class StationData {
        public String sourceItem;
        public String productItem;
        public Pos stationPos;
        public Pos inputPos;
        public Pos outputPos;
        public int repeatCount = 1;

        public StationData() {}
    }

    @SuppressWarnings("unused")
    public static class Pos {
        public int x, y, z;
        public Pos() {}
        public Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    // ==================== World-aware config path ====================

    private static String getCurrentWorldId() {
        Minecraft client = Minecraft.getInstance();
        if (client.getCurrentServer() != null) {
            return "server_" + sanitize(client.getCurrentServer().ip);
        }
        if (client.getSingleplayerServer() != null) {
            try {
                String name = client.getSingleplayerServer().getWorldData().getLevelName();
                return "world_" + sanitize(name);
            } catch (Exception ignored) {
            }
        }
        return "default";
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static Path getConfigPath() {
        return BASE_DIR.resolve(getCurrentWorldId() + ".json");
    }

    // ==================== Init ====================

    private static void ensureLoaded() {
        String worldId = getCurrentWorldId();
        if (!loaded || !worldId.equals(currentWorldId)) {
            currentWorldId = worldId;
            resetInMemory();
            stations.clear();
            load();
            loaded = true;
        }
    }

    private static void resetInMemory() {
        sourceItem = null;
        productItem = null;
        stationPos = null;
        inputPos = null;
        outputPos = null;
        repeatCount = 1;
        currentStationName = null;
    }

    /**
     * Populates in-memory fields from a StationData record.
     */
    private static void applyStationData(StationData data) {
        sourceItem = null;
        productItem = null;
        stationPos = null;
        inputPos = null;
        outputPos = null;
        repeatCount = 1;

        if (data == null) return;

        if (data.sourceItem != null) {
            ResourceLocation id = ResourceLocation.tryParse(data.sourceItem);
            if (id != null) sourceItem = BuiltInRegistries.ITEM.get(id);
        }
        if (data.productItem != null) {
            ResourceLocation id = ResourceLocation.tryParse(data.productItem);
            if (id != null) productItem = BuiltInRegistries.ITEM.get(id);
        }
        if (data.stationPos != null) {
            stationPos = new BlockPos(data.stationPos.x, data.stationPos.y, data.stationPos.z);
        }
        if (data.inputPos != null) {
            inputPos = new BlockPos(data.inputPos.x, data.inputPos.y, data.inputPos.z);
        }
        if (data.outputPos != null) {
            outputPos = new BlockPos(data.outputPos.x, data.outputPos.y, data.outputPos.z);
        }
        repeatCount = data.repeatCount;
    }

    /**
     * Builds a StationData from current in-memory state.
     */
    private static StationData captureCurrentState() {
        StationData data = new StationData();
        if (sourceItem != null) data.sourceItem = BuiltInRegistries.ITEM.getKey(sourceItem).toString();
        if (productItem != null) data.productItem = BuiltInRegistries.ITEM.getKey(productItem).toString();
        if (stationPos != null) data.stationPos = new Pos(stationPos.getX(), stationPos.getY(), stationPos.getZ());
        if (inputPos != null) data.inputPos = new Pos(inputPos.getX(), inputPos.getY(), inputPos.getZ());
        if (outputPos != null) data.outputPos = new Pos(outputPos.getX(), outputPos.getY(), outputPos.getZ());
        data.repeatCount = repeatCount;
        return data;
    }

    // ==================== Getters ====================

    public static Item getSourceItem() { ensureLoaded(); return sourceItem; }
    public static Item getProductItem() { ensureLoaded(); return productItem; }
    public static BlockPos getStationPos() { ensureLoaded(); return stationPos; }
    public static BlockPos getInputPos() { ensureLoaded(); return inputPos; }
    public static BlockPos getOutputPos() { ensureLoaded(); return outputPos; }
    public static int getRepeatCount() { ensureLoaded(); return repeatCount; }
    public static String getCurrentStationName() { ensureLoaded(); return currentStationName; }

    // ==================== Setters (auto-save) ====================

    public static void setSourceItem(Item item) { ensureLoaded(); sourceItem = item; save(); }
    public static void setProductItem(Item item) { ensureLoaded(); productItem = item; save(); }
    public static void setStationPos(BlockPos pos) { ensureLoaded(); stationPos = pos; save(); }
    public static void setInputPos(BlockPos pos) { ensureLoaded(); inputPos = pos; save(); }
    public static void setOutputPos(BlockPos pos) { ensureLoaded(); outputPos = pos; save(); }
    public static void setRepeatCount(int count) { ensureLoaded(); repeatCount = count; save(); }

    // ==================== Individual clear ====================

    public static void clearSourceItem() { ensureLoaded(); sourceItem = null; save(); }
    public static void clearProductItem() { ensureLoaded(); productItem = null; save(); }
    public static void clearStationPos() { ensureLoaded(); stationPos = null; save(); }
    public static void clearInputPos() { ensureLoaded(); inputPos = null; save(); }
    public static void clearOutputPos() { ensureLoaded(); outputPos = null; save(); }
    public static void clearRepeatCount() { ensureLoaded(); repeatCount = 1; save(); }

    // ==================== Clear all ====================

    public static void clear() {
        ensureLoaded();
        resetInMemory();
        save();
    }

    // ==================== Validation ====================

    public static boolean isReady() {
        ensureLoaded();
        return productItem != null && inputPos != null && outputPos != null;
    }

    public static boolean hasSourceItem() {
        ensureLoaded();
        return sourceItem != null;
    }

    public static String getMissingParams() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder();
        if (productItem == null) sb.append("product, ");
        if (inputPos == null) sb.append("input, ");
        if (outputPos == null) sb.append("output, ");
        if (sb.length() > 0) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    // ==================== Multi-station API ====================

    /**
     * Saves the current in-memory state as a named station and sets it as active.
     */
    public static void saveCurrentStation(String name) {
        ensureLoaded();
        StationData data = captureCurrentState();
        stations.put(name, data);
        currentStationName = name;
        save();
    }

    /**
     * Loads a named station's configuration into in-memory state.
     * @param name the station name to load
     * @return true if the station was found and loaded
     */
    public static boolean loadStation(String name) {
        ensureLoaded();
        StationData data = stations.get(name);
        if (data == null) return false;
        applyStationData(data);
        currentStationName = name;
        save();
        return true;
    }

    /**
     * Deletes a named station. If the deleted station was active,
     * in-memory state is cleared.
     * @param name the station name to delete
     * @return true if the station was found and deleted
     */
    public static boolean deleteStation(String name) {
        ensureLoaded();
        if (stations.remove(name) == null) return false;
        if (name.equals(currentStationName)) {
            resetInMemory();
            currentStationName = null;
        }
        save();
        return true;
    }

    /**
     * @return an unmodifiable list of all saved station names
     */
    public static List<String> getStationNames() {
        ensureLoaded();
        return List.copyOf(stations.keySet());
    }

    /**
     * @return an unmodifiable map of all saved station data (for external consumers)
     */
    public static Map<String, StationData> getStationsData() {
        ensureLoaded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(stations));
    }

    /**
     * Finds the nearest saved station (by stationPos) to the player's current
     * position, loads it, and returns its name.
     *
     * @return the name of the nearest station, or null if no stations have valid positions
     */
    public static String findNearestStation() {
        ensureLoaded();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return null;

        BlockPos playerPos = client.player.blockPosition();
        String nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (var entry : stations.entrySet()) {
            StationData data = entry.getValue();
            if (data.stationPos == null) continue;
            BlockPos pos = new BlockPos(data.stationPos.x, data.stationPos.y, data.stationPos.z);
            double distSq = playerPos.distSqr(pos);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entry.getKey();
            }
        }

        if (nearest == null) return null;

        // Auto-load the nearest station
        StationData data = stations.get(nearest);
        applyStationData(data);
        currentStationName = nearest;
        save();
        return nearest;
    }

    /**
     * Computes the Euclidean distance from the player to a given station's position.
     * @return distance in meters, or -1 if the station or player is unavailable
     */
    public static double distanceTo(String name) {
        ensureLoaded();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return -1;

        StationData data = stations.get(name);
        if (data == null || data.stationPos == null) return -1;

        BlockPos pos = new BlockPos(data.stationPos.x, data.stationPos.y, data.stationPos.z);
        return Math.sqrt(client.player.blockPosition().distSqr(pos));
    }

    // ==================== Persistence ====================

    private static synchronized void save() {
        Path path = getConfigPath();
        WorldConfig config = new WorldConfig();

        // Always persist current in-memory state into the active station
        if (currentStationName != null) {
            StationData currentData = captureCurrentState();
            stations.put(currentStationName, currentData);
            config.activeStation = currentStationName;
        } else {
            config.activeStation = null;
        }
        config.stations = new LinkedHashMap<>(stations);

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config));
        } catch (IOException ignored) {
        }
    }

    private static synchronized void load() {
        Path path = getConfigPath();
        if (!Files.exists(path)) return;

        try {
            String json = Files.readString(path);

            // Try new multi-station format first
            WorldConfig config = GSON.fromJson(json, WorldConfig.class);
            if (config != null && config.stations != null && !config.stations.isEmpty()) {
                stations.clear();
                stations.putAll(config.stations);

                // Load active station
                if (config.activeStation != null && stations.containsKey(config.activeStation)) {
                    applyStationData(stations.get(config.activeStation));
                    currentStationName = config.activeStation;
                } else {
                    resetInMemory();
                    currentStationName = null;
                }
                return;
            }

            // Backward compatibility: old flat format
            StateDataV1 oldData = GSON.fromJson(json, StateDataV1.class);
            if (oldData != null) {
                stations.clear();
                StationData migrated = migrateV1Data(oldData);
                String defaultName = "(默认)";
                stations.put(defaultName, migrated);
                applyStationData(migrated);
                currentStationName = defaultName;
                config = new WorldConfig();
                config.activeStation = defaultName;
                config.stations = new LinkedHashMap<>(stations);
                try {
                    Files.writeString(path, GSON.toJson(config));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ==================== Backward compatibility ====================

    @SuppressWarnings("unused")
    private static class StateDataV1 {
        String sourceItem;
        String productItem;
        Pos stationPos;
        Pos inputPos;
        Pos outputPos;
        int repeatCount = 1;
    }

    private static StationData migrateV1Data(StateDataV1 old) {
        StationData data = new StationData();
        data.sourceItem = old.sourceItem;
        data.productItem = old.productItem;
        data.stationPos = old.stationPos;
        data.inputPos = old.inputPos;
        data.outputPos = old.outputPos;
        data.repeatCount = old.repeatCount;
        return data;
    }
}
