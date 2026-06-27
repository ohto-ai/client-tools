package indi.ohtoai.tool.client_tools.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global (non-world-specific) Client Tools settings.
 *
 * <p>Stored in {@code config/client-tools/global.json}.
 */
public final class ClientToolsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
        .resolve("client-tools").resolve("global.json");

    private static boolean autoJump = false;
    private static boolean cbowEnabled = false;
    private static boolean criptideEnabled = false;
    private static boolean loaded = false;

    private ClientToolsConfig() {}

    @SuppressWarnings("unused")
    private static class Data {
        boolean autoJump;
        boolean cbowEnabled;
        boolean criptideEnabled;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            Data data = GSON.fromJson(Files.readString(CONFIG_PATH), Data.class);
            if (data != null) {
                autoJump = data.autoJump;
                cbowEnabled = data.cbowEnabled;
                criptideEnabled = data.criptideEnabled;
            }
        } catch (IOException ignored) {}
    }

    public static boolean isAutoJump() {
        ensureLoaded();
        return autoJump;
    }

    public static void setAutoJump(boolean v) {
        ensureLoaded();
        autoJump = v;
        save();
    }

    public static boolean isCbowEnabled() {
        ensureLoaded();
        return cbowEnabled;
    }

    public static void setCbowEnabled(boolean v) {
        ensureLoaded();
        cbowEnabled = v;
        save();
    }

    public static boolean isCriptideEnabled() {
        ensureLoaded();
        return criptideEnabled;
    }

    public static void setCriptideEnabled(boolean v) {
        ensureLoaded();
        criptideEnabled = v;
        save();
    }

    private static void save() {
        Data data = new Data();
        data.autoJump = autoJump;
        data.cbowEnabled = cbowEnabled;
        data.criptideEnabled = criptideEnabled;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (IOException ignored) {}
    }
}
