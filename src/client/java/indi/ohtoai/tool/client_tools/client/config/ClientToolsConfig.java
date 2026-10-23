package indi.ohtoai.tool.client_tools.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static boolean csneakEnabled = false;
    private static boolean p2pListenEnabled = true;
    private static List<String> p2pGroupMembers = new ArrayList<>();
    private static int p2pUdpPort = 0;
    private static String p2pPassword = "";
    private static Map<String, String> p2pPlayerPasswords = new HashMap<>();
    private static String p2pAlias = "em";
    private static boolean tpAutoEnabled = false;
    private static String tpAutoMode = "deny_all";
    private static String tpahereAutoMode = "deny_all";
    private static List<String> tpAutoWhitelist = new ArrayList<>();
    private static List<String> tpahereWhitelist = new ArrayList<>();
    private static boolean loaded = false;

    private ClientToolsConfig() {}

    @SuppressWarnings("unused")
    private static class Data {
        boolean autoJump;
        boolean cbowEnabled;
        boolean criptideEnabled;
        boolean csneakEnabled;
        boolean p2pListenEnabled;
        List<String> p2pGroupMembers;
        int p2pUdpPort;
        String p2pPassword;
        Map<String, String> p2pPlayerPasswords;
        String p2pAlias;
        boolean tpAutoEnabled;
        String tpAutoMode;
        String tpahereAutoMode;
        List<String> tpAutoWhitelist;
        List<String> tpahereWhitelist;
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
                csneakEnabled = data.csneakEnabled;
                if (data.p2pGroupMembers != null) p2pGroupMembers = data.p2pGroupMembers;
                p2pListenEnabled = data.p2pListenEnabled;
                p2pUdpPort = data.p2pUdpPort;
                if (data.p2pPassword != null) p2pPassword = data.p2pPassword;
                if (data.p2pPlayerPasswords != null) p2pPlayerPasswords = data.p2pPlayerPasswords;
                if (data.p2pAlias != null) p2pAlias = data.p2pAlias;
                tpAutoEnabled = data.tpAutoEnabled;
                if (data.tpAutoMode != null) tpAutoMode = data.tpAutoMode;
                if (data.tpahereAutoMode != null) tpahereAutoMode = data.tpahereAutoMode;
                if (data.tpAutoWhitelist != null) tpAutoWhitelist = data.tpAutoWhitelist;
                if (data.tpahereWhitelist != null) tpahereWhitelist = data.tpahereWhitelist;
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

    public static boolean isCsneakEnabled() {
        ensureLoaded();
        return csneakEnabled;
    }

    public static void setCsneakEnabled(boolean v) {
        ensureLoaded();
        csneakEnabled = v;
        save();
    }

    public static boolean isP2pListenEnabled() {
        ensureLoaded();
        return p2pListenEnabled;
    }

    public static void setP2pListenEnabled(boolean v) {
        ensureLoaded();
        p2pListenEnabled = v;
        save();
    }

    public static List<String> getP2pGroupMembers() {
        ensureLoaded();
        return p2pGroupMembers;
    }

    public static void setP2pGroupMembers(List<String> v) {
        ensureLoaded();
        p2pGroupMembers = v;
        save();
    }

    public static int getP2pUdpPort() {
        ensureLoaded();
        return p2pUdpPort;
    }

    public static void setP2pUdpPort(int v) {
        ensureLoaded();
        p2pUdpPort = v;
        save();
    }

    public static String getP2pPassword() {
        ensureLoaded();
        return p2pPassword;
    }

    public static void setP2pPassword(String v) {
        ensureLoaded();
        p2pPassword = v != null ? v : "";
        save();
    }

    public static Map<String, String> getP2pPlayerPasswords() {
        ensureLoaded();
        return p2pPlayerPasswords;
    }

    public static void setP2pPlayerPasswords(Map<String, String> v) {
        ensureLoaded();
        p2pPlayerPasswords = v != null ? v : new HashMap<>();
        save();
    }

    public static String getP2pAlias() {
        ensureLoaded();
        return p2pAlias;
    }

    public static void setP2pAlias(String v) {
        ensureLoaded();
        p2pAlias = v != null ? v : "em";
        save();
    }

    // ── TPA Auto ────────────────────────────────────────────────

    public static boolean isTpAutoEnabled() {
        ensureLoaded();
        return tpAutoEnabled;
    }

    public static void setTpAutoEnabled(boolean v) {
        ensureLoaded();
        tpAutoEnabled = v;
        save();
    }

    public static String getTpAutoMode() {
        ensureLoaded();
        return tpAutoMode;
    }

    public static void setTpAutoMode(String v) {
        ensureLoaded();
        tpAutoMode = v != null ? v : "deny_all";
        save();
    }

    public static String getTpahereAutoMode() {
        ensureLoaded();
        return tpahereAutoMode;
    }

    public static void setTpahereAutoMode(String v) {
        ensureLoaded();
        tpahereAutoMode = v != null ? v : "deny_all";
        save();
    }

    public static List<String> getTpAutoWhitelist() {
        ensureLoaded();
        return tpAutoWhitelist;
    }

    public static void setTpAutoWhitelist(List<String> v) {
        ensureLoaded();
        tpAutoWhitelist = v != null ? v : new ArrayList<>();
        save();
    }

    public static List<String> getTpahereWhitelist() {
        ensureLoaded();
        return tpahereWhitelist;
    }

    public static void setTpahereWhitelist(List<String> v) {
        ensureLoaded();
        tpahereWhitelist = v != null ? v : new ArrayList<>();
        save();
    }

    private static void save() {
        Data data = new Data();
        data.autoJump = autoJump;
        data.cbowEnabled = cbowEnabled;
        data.criptideEnabled = criptideEnabled;
        data.csneakEnabled = csneakEnabled;
        data.p2pListenEnabled = p2pListenEnabled;
        data.p2pGroupMembers = p2pGroupMembers;
        data.p2pUdpPort = p2pUdpPort;
        data.p2pPassword = p2pPassword.isEmpty() ? null : p2pPassword;
        data.p2pPlayerPasswords = p2pPlayerPasswords.isEmpty() ? null : p2pPlayerPasswords;
        data.p2pAlias = p2pAlias.isEmpty() ? null : p2pAlias;
        data.tpAutoEnabled = tpAutoEnabled;
        data.tpAutoMode = tpAutoMode.isEmpty() ? null : tpAutoMode;
        data.tpahereAutoMode = tpahereAutoMode.isEmpty() ? null : tpahereAutoMode;
        data.tpAutoWhitelist = tpAutoWhitelist.isEmpty() ? null : tpAutoWhitelist;
        data.tpahereWhitelist = tpahereWhitelist.isEmpty() ? null : tpahereWhitelist;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (IOException ignored) {}
    }
}
