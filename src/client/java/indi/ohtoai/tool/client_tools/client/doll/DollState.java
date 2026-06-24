package indi.ohtoai.tool.client_tools.client.doll;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persists the list of doll-ified items.
 *
 * <p>Global scope — stored at {@code config/client-tools/dolls.json}.
 * Format is a simple JSON array:
 * <pre>{@code
 * ["minecraft:apple", "minecraft:diamond"]
 * }</pre>
 */
public class DollState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
        .resolve("client-tools").resolve("dolls.json");

    private static final Set<String> dollItems = new LinkedHashSet<>();
    private static boolean loaded = false;

    private DollState() {}

    @SuppressWarnings("unused")
    private static class Data {
        List<String> items = new ArrayList<>();
    }

    // --- Public API ---

    public static void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    /** Returns an unmodifiable snapshot of doll-ified item IDs. */
    public static Set<String> getDollItems() {
        ensureLoaded();
        return Collections.unmodifiableSet(new LinkedHashSet<>(dollItems));
    }

    /** Adds an item. Returns {@code true} if added, {@code false} if already present. */
    public static boolean addDollItem(String itemId) {
        ensureLoaded();
        if (dollItems.add(itemId)) {
            save();
            return true;
        }
        return false;
    }

    /** Removes an item. Returns {@code true} if removed, {@code false} if not present. */
    public static boolean removeDollItem(String itemId) {
        ensureLoaded();
        if (dollItems.remove(itemId)) {
            save();
            return true;
        }
        return false;
    }

    /** Clears all doll assignments. */
    public static void clearAll() {
        ensureLoaded();
        if (!dollItems.isEmpty()) {
            dollItems.clear();
            save();
        }
    }

    public static boolean contains(String itemId) {
        ensureLoaded();
        return dollItems.contains(itemId);
    }

    public static boolean isEmpty() {
        ensureLoaded();
        return dollItems.isEmpty();
    }

    // --- Persistence ---

    private static synchronized void save() {
        Data data = new Data();
        data.items = new ArrayList<>(dollItems);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (IOException ignored) {
        }
    }

    private static synchronized void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            Data data = GSON.fromJson(Files.readString(CONFIG_PATH), Data.class);
            if (data != null && data.items != null) {
                dollItems.addAll(data.items);
            }
        } catch (IOException ignored) {
        }
    }
}
