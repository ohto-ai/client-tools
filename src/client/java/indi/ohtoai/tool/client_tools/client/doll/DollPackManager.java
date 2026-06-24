package indi.ohtoai.tool.client_tools.client.doll;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates a dynamic resource pack containing doll model overrides,
 * enables it in the client options, and triggers a resource reload.
 *
 * <p>The pack is written to {@code <gameDir>/resourcepacks/client-tools-dolls/}.
 * Each doll-ified item gets a model JSON that inherits from the
 * {@code template_doll} 3D model and passes the original item's texture
 * as {@code layer0}.
 */
public class DollPackManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PACK_NAME = "client-tools-dolls";

    private DollPackManager() {}

    /**
     * Creates the pack directory with a minimal {@code pack.mcmeta} on startup
     * so that Minecraft's resource pack scanner discovers it during its next scan.
     *
     * <p>Call this once in {@code ClientModInitializer.onInitializeClient()}.
     * Without this, the first {@code /cdoll add} call creates the directory
     * but the scanner may cache a stale view.
     */
    public static void initPackDirectory() {
        Minecraft client = Minecraft.getInstance();
        Path packDir = client.gameDirectory.toPath().resolve("resourcepacks").resolve(PACK_NAME);
        try {
            Files.createDirectories(packDir);
            int packFormat = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
            Map<String, Object> packMeta = Map.of(
                "pack", Map.of(
                    "pack_format", packFormat,
                    "supported_formats", new int[]{Math.max(22, packFormat), 99},
                    "description", "Client Tools - Doll Item Overrides"
                )
            );
            Files.writeString(packDir.resolve("pack.mcmeta"), GSON.toJson(packMeta));
        } catch (IOException ignored) {
        }
    }

    /**
     * Fully regenerates the doll resource pack, ensures it is enabled
     * in the client options, and triggers a resource reload.
     *
     * <p>Call this from the client thread after modifying {@link DollState}.
     */
    public static void applyChanges() {
        try {
            generatePack();
            ensurePackEnabled();
        } catch (IOException e) {
            // Silently handled — feedback is given by the caller
        }
        reloadResources();
    }

    // --- Pack generation ---

    private static void generatePack() throws IOException {
        Minecraft client = Minecraft.getInstance();
        Path packDir = client.gameDirectory.toPath().resolve("resourcepacks").resolve(PACK_NAME);
        Path assetsDir = packDir.resolve("assets");

        // Clean old files to avoid orphaned models
        if (Files.exists(assetsDir)) {
            deleteDirectory(assetsDir);
        }

        Set<String> items = DollState.getDollItems();
        if (items.isEmpty()) {
            // Nothing to generate — still write pack.mcmeta so the pack
            // remains valid in case items are added later
            return;
        }

        Files.createDirectories(packDir);

        // Write pack.mcmeta
        int packFormat = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
        Map<String, Object> packMeta = Map.of(
            "pack", Map.of(
                "pack_format", packFormat,
                "supported_formats", new int[]{Math.max(22, packFormat), 99},
                "description", "Client Tools - Doll Item Overrides"
            )
        );
        Files.writeString(packDir.resolve("pack.mcmeta"), GSON.toJson(packMeta));

        // Generate one model JSON per doll item
        for (String itemId : items) {
            generateModelFile(packDir, itemId);
        }
    }

    private static void generateModelFile(Path packDir, String itemId) throws IOException {
        // itemId: "namespace:path" e.g. "minecraft:apple"
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return;

        String namespace = rl.getNamespace();
        String path = rl.getPath();

        Path modelFile = packDir.resolve("assets")
            .resolve(namespace)
            .resolve("models")
            .resolve("item")
            .resolve(path + ".json");
        Files.createDirectories(modelFile.getParent());

        // The texture reference uses namespace:item/path format so modded
        // items resolve correctly (vanilla items work with both formats)
        String textureRef = namespace + ":item/" + path;

        Map<String, Object> model = Map.of(
            "parent", "minecraft:item/template_doll",
            "textures", Map.of("layer0", textureRef)
        );
        Files.writeString(modelFile, GSON.toJson(model));
    }

    // --- Options management ---

    private static void ensurePackEnabled() {
        Minecraft client = Minecraft.getInstance();
        if (!client.options.resourcePacks.contains(PACK_NAME)) {
            client.options.resourcePacks.add(PACK_NAME);
            client.options.save();
        }
    }

    // --- Resource reload ---

    private static void reloadResources() {
        // reloadResourcePacks() internally handles:
        //   1. Scanning resourcepacks/ directory
        //   2. Syncing enabled packs from options
        //   3. Full resource reload (textures, models, sounds)
        // We pre-create the pack directory on startup via initPackDirectory()
        // so the scanner can discover it even on the first reload.
        Minecraft.getInstance().reloadResourcePacks();
    }

    // --- Utility ---

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
        }
    }
}
