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

/**
 * Stores the current /ccraft configuration.
 * State is persisted per-world/server so that different servers
 * can have different coordinates, items, etc.
 *
 * Files are stored under {@code config/client-tools/ccraft/<world-id>.json}.
 */
public class CcraftState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BASE_DIR = FabricLoader.getInstance().getConfigDir()
        .resolve("client-tools").resolve("ccraft");

    private static Item sourceItem;
    private static Item productItem;
    private static BlockPos stationPos;
    private static BlockPos inputPos;
    private static BlockPos outputPos;
    private static int repeatCount = 1; // -1 = infinite, >0 = finite
    private static boolean loaded = false;
    private static String currentWorldId = "";

    private CcraftState() {}

    // --- Serializable data holder ---
    @SuppressWarnings("unused")
    private static class StateData {
        String sourceItem;
        String productItem;
        Pos stationPos;
        Pos inputPos;
        Pos outputPos;
        int repeatCount = 1;
    }

    @SuppressWarnings("unused")
    private static class Pos {
        int x, y, z;
        Pos() {}
        Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    // --- World-aware config path ---

    /**
     * Returns a unique identifier for the current world or server.
     * Multiplayer: {@code server_<sanitized-ip>}
     * Singleplayer: {@code world_<sanitized-world-name>}
     * Fallback: {@code default}
     */
    private static String getCurrentWorldId() {
        Minecraft client = Minecraft.getInstance();
        // Multiplayer (including LAN)
        if (client.getCurrentServer() != null) {
            return "server_" + sanitize(client.getCurrentServer().ip);
        }
        // Singleplayer
        if (client.getSingleplayerServer() != null) {
            try {
                String name = client.getSingleplayerServer().getWorldData().getLevelName();
                return "world_" + sanitize(name);
            } catch (Exception ignored) {
                // Fall through to default
            }
        }
        return "default";
    }

    /** Replace filesystem-unsafe characters with underscores. */
    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static Path getConfigPath() {
        return BASE_DIR.resolve(getCurrentWorldId() + ".json");
    }

    // --- Init ---

    /**
     * Loads the config for the current world if not yet loaded,
     * or reloads if the player has switched worlds since last access.
     */
    private static void ensureLoaded() {
        String worldId = getCurrentWorldId();
        if (!loaded || !worldId.equals(currentWorldId)) {
            currentWorldId = worldId;
            // Reset all fields so we load fresh from the new world's config
            sourceItem = null;
            productItem = null;
            stationPos = null;
            inputPos = null;
            outputPos = null;
            repeatCount = 1;
            load();
            loaded = true;
        }
    }

    // --- Getters ---

    public static Item getSourceItem() { ensureLoaded(); return sourceItem; }
    public static Item getProductItem() { ensureLoaded(); return productItem; }
    public static BlockPos getStationPos() { ensureLoaded(); return stationPos; }
    public static BlockPos getInputPos() { ensureLoaded(); return inputPos; }
    public static BlockPos getOutputPos() { ensureLoaded(); return outputPos; }
    public static int getRepeatCount() { ensureLoaded(); return repeatCount; }

    // --- Setters (auto-save) ---

    public static void setSourceItem(Item item) { ensureLoaded(); sourceItem = item; save(); }
    public static void setProductItem(Item item) { ensureLoaded(); productItem = item; save(); }
    public static void setStationPos(BlockPos pos) { ensureLoaded(); stationPos = pos; save(); }
    public static void setInputPos(BlockPos pos) { ensureLoaded(); inputPos = pos; save(); }
    public static void setOutputPos(BlockPos pos) { ensureLoaded(); outputPos = pos; save(); }
    public static void setRepeatCount(int count) { ensureLoaded(); repeatCount = count; save(); }

    // --- Validation ---

    public static boolean isReady() {
        ensureLoaded();
        return productItem != null && inputPos != null && outputPos != null;
    }

    /**
     * Returns true if the user explicitly set a source item (legacy mode).
     * When false, the system uses auto-detect multi-source mode.
     */
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

    // --- Clear ---

    public static void clear() {
        ensureLoaded();
        sourceItem = null;
        productItem = null;
        stationPos = null;
        inputPos = null;
        outputPos = null;
        save();
    }

    // --- Persistence ---

    private static synchronized void save() {
        Path path = getConfigPath();
        StateData data = new StateData();
        if (sourceItem != null) data.sourceItem = BuiltInRegistries.ITEM.getKey(sourceItem).toString();
        if (productItem != null) data.productItem = BuiltInRegistries.ITEM.getKey(productItem).toString();
        if (stationPos != null) data.stationPos = new Pos(stationPos.getX(), stationPos.getY(), stationPos.getZ());
        if (inputPos != null) data.inputPos = new Pos(inputPos.getX(), inputPos.getY(), inputPos.getZ());
        if (outputPos != null) data.outputPos = new Pos(outputPos.getX(), outputPos.getY(), outputPos.getZ());
        data.repeatCount = repeatCount;

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data));
        } catch (IOException ignored) {
            // Silently fail — state just won't persist
        }
    }

    private static synchronized void load() {
        Path path = getConfigPath();
        if (!Files.exists(path)) return;

        try {
            String json = Files.readString(path);
            StateData data = GSON.fromJson(json, StateData.class);
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
        } catch (IOException ignored) {
            // Corrupt file — start fresh
        }
    }
}
