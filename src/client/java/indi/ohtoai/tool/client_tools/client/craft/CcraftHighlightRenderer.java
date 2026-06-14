package indi.ohtoai.tool.client_tools.client.craft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Renders wireframe block highlights, item icons, and floating text labels
 * for the three /ccraft coordinate positions (station, input chest, output chest).
 *
 * <p>Uses {@code WorldRenderEvents.BEFORE_DEBUG_RENDER} so that
 * {@link WorldRenderContext#consumers()} is available for text and icon
 * rendering.
 *
 * <p>Default colors:
 * <ul>
 *   <li>Station — green ({@code #55FF55})</li>
 *   <li>Input chest — blue ({@code #8BAAFF})</li>
 *   <li>Output chest — gold ({@code #FFAA00})</li>
 * </ul>
 * Colors and duration can be customized via {@link #trigger(int, int, int, int)}.
 */
public class CcraftHighlightRenderer {

    // ---- Defaults ----
    public static final int DEFAULT_DURATION_TICKS = 60; // 3 seconds at 20 TPS
    public static final int DEFAULT_STATION_COLOR = 0x55FF55; // green
    public static final int DEFAULT_INPUT_COLOR = 0x8BAAFF;   // blue
    public static final int DEFAULT_OUTPUT_COLOR = 0xFFAA00;  // gold

    private static final int MAX_MATERIAL_ICONS = 8; // cap to avoid visual clutter
    private static int ticksRemaining = 0;

    // ---- Customizable colors ----
    private static int stationColor = DEFAULT_STATION_COLOR;
    private static int inputColor = DEFAULT_INPUT_COLOR;
    private static int outputColor = DEFAULT_OUTPUT_COLOR;

    private CcraftHighlightRenderer() {}

    // ==================== Public API ====================

    /** Start or restart the highlight with default duration and colors. */
    public static void trigger() {
        trigger(DEFAULT_DURATION_TICKS, DEFAULT_STATION_COLOR, DEFAULT_INPUT_COLOR, DEFAULT_OUTPUT_COLOR);
    }

    /** Start or restart the highlight with a custom duration and default colors. */
    public static void trigger(int durationTicks) {
        trigger(durationTicks, DEFAULT_STATION_COLOR, DEFAULT_INPUT_COLOR, DEFAULT_OUTPUT_COLOR);
    }

    /**
     * Start or restart the highlight with a custom duration and colors.
     *
     * @param durationTicks how many ticks to display (20 ticks = 1 second)
     * @param stationColor  ARGB color for the station wireframe/label
     * @param inputColor    ARGB color for the input chest wireframe/label
     * @param outputColor   ARGB color for the output chest wireframe/label
     */
    public static void trigger(int durationTicks, int stationColor, int inputColor, int outputColor) {
        ticksRemaining = Math.max(1, durationTicks);
        CcraftHighlightRenderer.stationColor = stationColor;
        CcraftHighlightRenderer.inputColor = inputColor;
        CcraftHighlightRenderer.outputColor = outputColor;
    }

    /** Immediately stop highlighting. */
    public static void reset() {
        ticksRemaining = 0;
    }

    /** Whether highlighting is currently active. */
    public static boolean isActive() {
        return ticksRemaining > 0;
    }

    /** Decrement the tick counter each client tick. */
    public static void tick() {
        if (ticksRemaining > 0) {
            ticksRemaining--;
        }
    }

    /** Get remaining ticks for display purposes. */
    public static int getTicksRemaining() {
        return ticksRemaining;
    }

    // ==================== Render Entry Point ====================

    /**
     * Registered to {@code WorldRenderEvents.BEFORE_DEBUG_RENDER}.
     */
    public static void render(WorldRenderContext context) {
        if (ticksRemaining <= 0) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        if (client.player == null) return;

        PoseStack poseStack = context.matrixStack();
        if (poseStack == null) return;

        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();

        // consumers() is non-null in BEFORE_DEBUG_RENDER
        MultiBufferSource consumers = context.consumers();
        if (consumers == null) return;

        // Fetch current positions (may be partially null)
        BlockPos station = CcraftState.getStationPos();
        BlockPos input = CcraftState.getInputPos();
        BlockPos output = CcraftState.getOutputPos();

        // Pre-compute RGB float components from stored ARGB colors
        float[] stationRGB = colorToRGB(stationColor);
        float[] inputRGB = colorToRGB(inputColor);
        float[] outputRGB = colorToRGB(outputColor);

        // --- Station ---
        if (station != null) {
            renderWireframe(poseStack, station, camPos, stationRGB[0], stationRGB[1], stationRGB[2], 0.8f);
            renderIcons(poseStack, station, camPos, camera, client, consumers,
                List.of(new ItemStack(Items.CRAFTING_TABLE)));
            renderLabel(poseStack, station, camPos, camera, client, consumers,
                "Crafting Station", stationColor | 0xFF000000);
        }

        // --- Input chest ---
        if (input != null) {
            List<ItemStack> inputIcons = getInputMaterialIcons(client);
            renderWireframe(poseStack, input, camPos, inputRGB[0], inputRGB[1], inputRGB[2], 0.8f);
            renderIcons(poseStack, input, camPos, camera, client, consumers, inputIcons);
            renderLabel(poseStack, input, camPos, camera, client, consumers,
                "Input Chest", inputColor | 0xFF000000);
        }

        // --- Output chest ---
        if (output != null) {
            Item productItem = CcraftState.getProductItem();
            List<ItemStack> outputIcons = productItem != null
                ? List.of(new ItemStack(productItem))
                : List.of(new ItemStack(Items.CHEST));
            renderWireframe(poseStack, output, camPos, outputRGB[0], outputRGB[1], outputRGB[2], 0.8f);
            renderIcons(poseStack, output, camPos, camera, client, consumers, outputIcons);
            renderLabel(poseStack, output, camPos, camera, client, consumers,
                "Output Chest", outputColor | 0xFF000000);
        }
    }

    // ==================== Color helpers ====================

    /**
     * Convert an RGB24 integer to a float[3] with components in [0, 1].
     */
    private static float[] colorToRGB(int color) {
        return new float[] {
            ((color >> 16) & 0xFF) / 255.0f,
            ((color >> 8) & 0xFF) / 255.0f,
            (color & 0xFF) / 255.0f
        };
    }

    // ==================== Material Icon Resolution ====================

    /**
     * Determines which item icons to show at the input chest position.
     *
     * <ol>
     *   <li>If the player explicitly set a source item via {@code /ccraft source},
     *       show that single item.</li>
     *   <li>If only a product item is set, look up all crafting recipes that
     *       produce it and collect their unique ingredient items.</li>
     *   <li>Otherwise fall back to a generic chest icon.</li>
     * </ol>
     */
    private static List<ItemStack> getInputMaterialIcons(Minecraft client) {
        // Priority 1: explicitly set source item
        Item sourceItem = CcraftState.getSourceItem();
        if (sourceItem != null) {
            return List.of(new ItemStack(sourceItem));
        }

        // Priority 2: derive from product recipes
        Item productItem = CcraftState.getProductItem();
        if (productItem != null && client.level != null) {
            List<ItemStack> materials = deriveIngredients(client, productItem);
            if (!materials.isEmpty()) {
                return materials;
            }
        }

        // Priority 3: fallback chest icon
        return List.of(new ItemStack(Items.CHEST));
    }

    /**
     * Looks up all crafting recipes whose result is {@code productItem} and
     * returns a deduplicated list of their ingredient items.
     */
    private static List<ItemStack> deriveIngredients(Minecraft client, Item productItem) {
        RecipeManager rm = client.level.getRecipeManager();
        HolderLookup.Provider reg = client.level.registryAccess();

        Set<Item> seen = new LinkedHashSet<>(); // preserve insertion order for stability
        List<RecipeHolder<CraftingRecipe>> allRecipes = rm.getAllRecipesFor(RecipeType.CRAFTING);

        for (RecipeHolder<CraftingRecipe> holder : allRecipes) {
            CraftingRecipe recipe = holder.value();

            // Check result item
            ItemStack result;
            try {
                result = recipe.getResultItem(reg);
            } catch (Exception e) {
                continue;
            }
            if (result.isEmpty() || result.getItem() != productItem) continue;

            // Collect unique ingredient items from this recipe
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing.isEmpty()) continue;
                ItemStack[] items = ing.getItems();
                if (items.length == 0) continue;
                // Pick the first matching item variant as the representative
                Item item = items[0].getItem();
                if (seen.size() < MAX_MATERIAL_ICONS) {
                    seen.add(item);
                }
            }
        }

        List<ItemStack> result = new ArrayList<>();
        for (Item item : seen) {
            result.add(new ItemStack(item));
        }
        return result;
    }

    // ==================== Wireframe Box ====================

    /**
     * Draws a wireframe outline of a 1x1x1 cube at the given block position.
     */
    private static void renderWireframe(PoseStack poseStack, BlockPos pos, Vec3 camPos,
                                        float r, float g, float b, float a) {
        poseStack.pushPose();

        double dx = pos.getX() - camPos.x;
        double dy = pos.getY() - camPos.y;
        double dz = pos.getZ() - camPos.z;
        poseStack.translate(dx, dy, dz);

        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // 12 edges of a unit cube [0,0,0] -> [1,1,1]
        // --- Bottom face (y=0) ---
        line(builder, matrix, 0, 0, 0, 1, 0, 0, r, g, b, a);
        line(builder, matrix, 1, 0, 0, 1, 0, 1, r, g, b, a);
        line(builder, matrix, 1, 0, 1, 0, 0, 1, r, g, b, a);
        line(builder, matrix, 0, 0, 1, 0, 0, 0, r, g, b, a);
        // --- Top face (y=1) ---
        line(builder, matrix, 0, 1, 0, 1, 1, 0, r, g, b, a);
        line(builder, matrix, 1, 1, 0, 1, 1, 1, r, g, b, a);
        line(builder, matrix, 1, 1, 1, 0, 1, 1, r, g, b, a);
        line(builder, matrix, 0, 1, 1, 0, 1, 0, r, g, b, a);
        // --- Vertical edges ---
        line(builder, matrix, 0, 0, 0, 0, 1, 0, r, g, b, a);
        line(builder, matrix, 1, 0, 0, 1, 1, 0, r, g, b, a);
        line(builder, matrix, 1, 0, 1, 1, 1, 1, r, g, b, a);
        line(builder, matrix, 0, 0, 1, 0, 1, 1, r, g, b, a);

        BufferUploader.drawWithShader(builder.build());

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);

        poseStack.popPose();
    }

    private static void line(BufferBuilder builder, Matrix4f matrix,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        builder.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
    }

    // ==================== Item Icons ====================

    /**
     * Renders one or more item icons floating above the block, billboarded to
     * face the camera. Multiple icons are spread horizontally in screen space.
     */
    private static void renderIcons(PoseStack poseStack, BlockPos pos, Vec3 camPos,
                                     Camera camera, Minecraft client, MultiBufferSource consumers,
                                     List<ItemStack> stacks) {
        if (stacks.isEmpty()) return;

        ItemRenderer itemRenderer = client.getItemRenderer();
        int count = stacks.size();
        float spacing = 0.55f; // world-unit spacing between adjacent icons

        for (int i = 0; i < count; i++) {
            ItemStack stack = stacks.get(i);

            poseStack.pushPose();

            // Position: centered on block, 0.5 blocks above the top surface
            poseStack.translate(
                pos.getX() + 0.5 - camPos.x,
                pos.getY() + 1.2 - camPos.y,
                pos.getZ() + 0.5 - camPos.z
            );

            // Billboard: face the camera
            poseStack.mulPose(camera.rotation());

            // Horizontal offset in billboard-local space so icons spread
            // left-to-right regardless of view angle
            float offsetX = (i - (count - 1) / 2.0f) * spacing;
            poseStack.translate(offsetX, 0.0f, 0.0f);

            // Scale the icon to a reasonable world size
            poseStack.scale(0.5f, 0.5f, 0.5f);

            itemRenderer.renderStatic(
                stack,
                ItemDisplayContext.GROUND,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                consumers,
                client.level,
                0
            );

            poseStack.popPose();
        }
    }

    // ==================== Floating Text Label ====================

    /**
     * Draws a floating text label above the icon(s), always facing the camera.
     * Text format: {@code "Label [x, y, z]"}.
     */
    private static void renderLabel(PoseStack poseStack, BlockPos pos, Vec3 camPos,
                                    Camera camera, Minecraft client, MultiBufferSource consumers,
                                    String name, int color) {
        String text = name + " [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";

        poseStack.pushPose();

        // Position: centered on block, above the icon
        poseStack.translate(
            pos.getX() + 0.5 - camPos.x,
            pos.getY() + 1.6 - camPos.y,
            pos.getZ() + 0.5 - camPos.z
        );

        // Billboard: face the camera
        poseStack.mulPose(camera.rotation());

        // Scale down to world size (negative X and Y mirror to right-side up)
        poseStack.scale(-0.025f, -0.025f, 0.025f);

        // Center the text horizontally
        float textWidth = client.font.width(text);
        float x = -textWidth / 2.0f;

        Matrix4f matrix = poseStack.last().pose();

        // SEE_THROUGH -> text visible through walls; FULL_BRIGHT -> always readable
        client.font.drawInBatch(
            text,
            x, 0.0f,
            color,
            false,                            // no drop shadow
            matrix,
            consumers,
            Font.DisplayMode.SEE_THROUGH,
            0x40000000,                       // semi-transparent background
            LightTexture.FULL_BRIGHT
        );

        poseStack.popPose();
    }
}
