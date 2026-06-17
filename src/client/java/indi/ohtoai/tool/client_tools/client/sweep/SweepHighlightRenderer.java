package indi.ohtoai.tool.client_tools.client.sweep;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Renders the cuboid outline, snake path, and active station marker
 * for the /csweep command.
 *
 * <p>Rendering is toggled independently:
 * <ul>
 *   <li>Outline (green) — {@code /csweep show outline}</li>
 *   <li>Path lines (cyan) — {@code /csweep show path}</li>
 *   <li>Active station marker (gold) — shown when executor is running</li>
 * </ul>
 */
public class SweepHighlightRenderer {

    private static final int OUTLINE_COLOR  = 0x55FF55;
    private static final int PATH_COLOR     = 0x55FFFF;
    private static final int VISITED_COLOR  = 0x888888;
    private static final int ACTIVE_COLOR   = 0xFFAA00;

    /** Color palette for multi-region outline rendering. */
    private static final int[] REGION_COLORS = {
        0x55FF55, 0x55AAFF, 0xFFAA55, 0xFF55FF,
        0x55FFAA, 0xAAAAFF, 0xFF5555, 0xAAFF55
    };

    private SweepHighlightRenderer() {}

    public static void tick() {}

    public static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;

        PoseStack poseStack = context.matrixStack();
        if (poseStack == null) return;

        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();

        MultiBufferSource consumers = context.consumers();
        if (consumers == null) return;

        boolean showOutline = SweepState.isShowOutline();
        boolean showPath = SweepState.isShowPath();
        SweepExecutor executor = SweepExecutor.getInstance();

        boolean active = executor.isRunning() || executor.isPaused();

        if (!showOutline && !showPath && !active) return;

        // --- Outline ---
        if (showOutline) {
            List<LitematicaIntegration.SubRegionBox> subRegions = SweepState.resolveSubRegions();
            SweepExecutor exe = SweepExecutor.getInstance();
            boolean activeSweep = exe.isRunning() || exe.isPaused();

            if (subRegions.size() > 1) {
                // Multi-region: render each sub-region with a distinct color
                for (int i = 0; i < subRegions.size(); i++) {
                    var box = subRegions.get(i);
                    int color = REGION_COLORS[i % REGION_COLORS.length];
                    float[] rgb = colorToRGB(color);
                    boolean isCurrent = activeSweep && i == exe.getCurrentRegionIndex();
                    float alpha = isCurrent ? 0.8f : 0.35f;
                    renderCuboidWireframe(poseStack, camPos,
                        Math.min(box.pos1().getX(), box.pos2().getX()),
                        Math.min(box.pos1().getY(), box.pos2().getY()),
                        Math.min(box.pos1().getZ(), box.pos2().getZ()),
                        Math.max(box.pos1().getX(), box.pos2().getX()),
                        Math.max(box.pos1().getY(), box.pos2().getY()),
                        Math.max(box.pos1().getZ(), box.pos2().getZ()),
                        rgb[0], rgb[1], rgb[2], alpha);
                }
            } else if (subRegions.size() == 1) {
                // Single region: classic green outline
                var box = subRegions.get(0);
                float[] rgb = colorToRGB(OUTLINE_COLOR);
                renderCuboidWireframe(poseStack, camPos,
                    Math.min(box.pos1().getX(), box.pos2().getX()),
                    Math.min(box.pos1().getY(), box.pos2().getY()),
                    Math.min(box.pos1().getZ(), box.pos2().getZ()),
                    Math.max(box.pos1().getX(), box.pos2().getX()),
                    Math.max(box.pos1().getY(), box.pos2().getY()),
                    Math.max(box.pos1().getZ(), box.pos2().getZ()),
                    rgb[0], rgb[1], rgb[2], 0.6f);
            } else {
                // No sub-regions: render single-block wireframe for individual pos if set
                BlockPos p1 = SweepState.getPos1();
                BlockPos p2 = SweepState.getPos2();
                float[] rgb = colorToRGB(OUTLINE_COLOR);
                if (p1 != null) {
                    renderBlockWireframe(poseStack, camPos, p1, rgb[0], rgb[1], rgb[2], 0.6f);
                } else if (p2 != null) {
                    renderBlockWireframe(poseStack, camPos, p2, rgb[0], rgb[1], rgb[2], 0.6f);
                }
            }
        }

        // --- Path lines ---
        List<Vec3> path = null;
        if (showPath) {
            if (active && executor.getRegionCount() > 1) {
                // Multi-region: concatenate all region paths
                path = executor.getFullConcatPath();
            } else if (active) {
                path = executor.getStationPath();
            } else {
                path = SweepExecutor.computePreviewPath();
            }
            if (path != null && path.size() >= 2) {
                if (active && executor.getCurrentStationIndex() > 0) {
                    int split = executor.getCurrentStationIndex();
                    float[] visitedRgb = colorToRGB(VISITED_COLOR);
                    float[] upcomingRgb = colorToRGB(PATH_COLOR);
                    if (split >= 2) {
                        renderPathRange(poseStack, camPos, path, 0, split,
                            visitedRgb[0], visitedRgb[1], visitedRgb[2], 0.35f);
                    }
                    if (split < path.size()) {
                        renderPathRange(poseStack, camPos, path, split - 1, path.size(),
                            upcomingRgb[0], upcomingRgb[1], upcomingRgb[2], 0.5f);
                    }
                } else {
                    float[] rgb = colorToRGB(PATH_COLOR);
                    renderPathRange(poseStack, camPos, path, 0, path.size(),
                        rgb[0], rgb[1], rgb[2], 0.5f);
                }
            }
        }

        // --- Active station marker (shown when running or paused) ---
        if (active) {
            Vec3 stationPos = executor.getCurrentStationPos();
            if (stationPos != null) {
                float[] rgb = colorToRGB(ACTIVE_COLOR);
                String prefix;
                if (executor.getRegionCount() > 1) {
                    prefix = executor.getCurrentRegionName() + " ";
                } else if (executor.isPaused()) {
                    prefix = "[PAUSED] ";
                } else {
                    prefix = "";
                }
                String label = executor.isPaused() ? prefix + "Station " : prefix + "Station ";
                renderStationMarker(poseStack, stationPos, camPos, rgb[0], rgb[1], rgb[2], 0.8f);
                renderProgressLabel(poseStack, stationPos, camPos, camera, client, consumers,
                    label, executor.getCurrentStationIndex() + 1, executor.getTotalStations());
            }
        }

        // --- Nearest station marker (set by /csweep nearest) ---
        if (!active && executor.hasNearestStation()) {
            Vec3 nearestPos = executor.getNearestStationPos();
            if (nearestPos != null) {
                // Bright white-yellow color, distinctive from the gold active marker
                float[] rgb = new float[]{1.0f, 0.9f, 0.3f};
                renderStationMarker(poseStack, nearestPos, camPos, rgb[0], rgb[1], rgb[2], 1.0f);
                String label = "> NEAREST < Station ";
                renderProgressLabel(poseStack, nearestPos, camPos, camera, client, consumers,
                    label, executor.getNearestStationIndex() + 1, executor.getTotalStations());
            }
        }
    }

    // --- Color ---

    private static float[] colorToRGB(int color) {
        return new float[] {
            ((color >> 16) & 0xFF) / 255.0f,
            ((color >> 8) & 0xFF) / 255.0f,
            (color & 0xFF) / 255.0f
        };
    }

    // --- Cuboid wireframe ---

    /**
     * Renders a cuboid wireframe with explicit bounds.
     * Coordinates are block-space (min inclusive, max inclusive).
     */
    private static void renderCuboidWireframe(PoseStack poseStack, Vec3 camPos,
                                               int minX, int minY, int minZ,
                                               int maxX, int maxY, int maxZ,
                                               float r, float g, float b, float a) {
        float x1 = minX - (float) camPos.x;
        float y1 = minY - (float) camPos.y;
        float z1 = minZ - (float) camPos.z;
        float x2 = maxX + 1 - (float) camPos.x;
        float y2 = maxY + 1 - (float) camPos.y;
        float z2 = maxZ + 1 - (float) camPos.z;

        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // Bottom face
        line(builder, matrix, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(builder, matrix, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(builder, matrix, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(builder, matrix, x1, y1, z2, x1, y1, z1, r, g, b, a);
        // Top face
        line(builder, matrix, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(builder, matrix, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(builder, matrix, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(builder, matrix, x1, y2, z2, x1, y2, z1, r, g, b, a);
        // Vertical edges
        line(builder, matrix, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(builder, matrix, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(builder, matrix, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(builder, matrix, x1, y1, z2, x1, y2, z2, r, g, b, a);

        BufferUploader.drawWithShader(builder.build());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
        poseStack.popPose();
    }

    /**
     * Draws a 1×1×1 wireframe at a single block position, used when only
     * one of pos1/pos2 has been set.
     */
    private static void renderBlockWireframe(PoseStack poseStack, Vec3 camPos, BlockPos pos,
                                              float r, float g, float b, float a) {
        float x1 = pos.getX() - (float) camPos.x;
        float y1 = pos.getY() - (float) camPos.y;
        float z1 = pos.getZ() - (float) camPos.z;
        float x2 = x1 + 1.0f;
        float y2 = y1 + 1.0f;
        float z2 = z1 + 1.0f;

        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // Bottom face
        line(builder, matrix, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(builder, matrix, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(builder, matrix, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(builder, matrix, x1, y1, z2, x1, y1, z1, r, g, b, a);
        // Top face
        line(builder, matrix, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(builder, matrix, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(builder, matrix, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(builder, matrix, x1, y2, z2, x1, y2, z1, r, g, b, a);
        // Vertical edges
        line(builder, matrix, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(builder, matrix, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(builder, matrix, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(builder, matrix, x1, y1, z2, x1, y2, z2, r, g, b, a);

        BufferUploader.drawWithShader(builder.build());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
        poseStack.popPose();
    }

    // --- Path lines ---

    /**
     * Draws connected line segments for a range of the station path.
     * Range is [start, end) — draws edges between consecutive stations
     * from {@code start} to {@code end-1}.
     */
    private static void renderPathRange(PoseStack poseStack, Vec3 camPos, List<Vec3> path,
                                         int start, int end,
                                         float r, float g, float b, float a) {
        if (end - start < 2) return; // need at least 2 points for one segment

        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(1.5f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = start; i < end - 1; i++) {
            Vec3 p0 = path.get(i);
            Vec3 p1 = path.get(i + 1);
            float p0x = (float) p0.x - (float) camPos.x;
            float p0y = (float) p0.y - (float) camPos.y;
            float p0z = (float) p0.z - (float) camPos.z;
            float p1x = (float) p1.x - (float) camPos.x;
            float p1y = (float) p1.y - (float) camPos.y;
            float p1z = (float) p1.z - (float) camPos.z;
            line(builder, matrix, p0x, p0y, p0z, p1x, p1y, p1z, r, g, b, a);
        }

        BufferUploader.drawWithShader(builder.build());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
        poseStack.popPose();
    }

    // --- Station marker ---

    private static void renderStationMarker(PoseStack poseStack, Vec3 station, Vec3 camPos,
                                             float r, float g, float b, float a) {
        float sx = (float) station.x - (float) camPos.x;
        float sy = (float) station.y - (float) camPos.y;
        float sz = (float) station.z - (float) camPos.z;
        float half = 0.5f;
        float beacon = 10.0f;

        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(3.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // Bottom face
        line(builder, matrix, sx - half, sy - half, sz - half, sx + half, sy - half, sz - half, r, g, b, a);
        line(builder, matrix, sx + half, sy - half, sz - half, sx + half, sy - half, sz + half, r, g, b, a);
        line(builder, matrix, sx + half, sy - half, sz + half, sx - half, sy - half, sz + half, r, g, b, a);
        line(builder, matrix, sx - half, sy - half, sz + half, sx - half, sy - half, sz - half, r, g, b, a);
        // Top face
        line(builder, matrix, sx - half, sy + half, sz - half, sx + half, sy + half, sz - half, r, g, b, a);
        line(builder, matrix, sx + half, sy + half, sz - half, sx + half, sy + half, sz + half, r, g, b, a);
        line(builder, matrix, sx + half, sy + half, sz + half, sx - half, sy + half, sz + half, r, g, b, a);
        line(builder, matrix, sx - half, sy + half, sz + half, sx - half, sy + half, sz - half, r, g, b, a);
        // Vertical edges
        line(builder, matrix, sx - half, sy - half, sz - half, sx - half, sy + half, sz - half, r, g, b, a);
        line(builder, matrix, sx + half, sy - half, sz - half, sx + half, sy + half, sz - half, r, g, b, a);
        line(builder, matrix, sx + half, sy - half, sz + half, sx + half, sy + half, sz + half, r, g, b, a);
        line(builder, matrix, sx - half, sy - half, sz + half, sx - half, sy + half, sz + half, r, g, b, a);
        // Beacon
        line(builder, matrix, sx, sy + half, sz, sx, sy + beacon, sz, r, g, b, a * 0.5f);

        BufferUploader.drawWithShader(builder.build());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
        poseStack.popPose();
    }

    // --- Progress label ---

    private static void renderProgressLabel(PoseStack poseStack, Vec3 station, Vec3 camPos,
                                             Camera camera, Minecraft client, MultiBufferSource consumers,
                                             String prefix, int current, int total) {
        String text = prefix + current + " / " + total;
        poseStack.pushPose();
        poseStack.translate(station.x - camPos.x, station.y + 2.0 - camPos.y, station.z - camPos.z);
        poseStack.mulPose(camera.rotation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);

        float textWidth = client.font.width(text);
        Matrix4f matrix = poseStack.last().pose();
        client.font.drawInBatch(text, -textWidth / 2.0f, 0.0f, 0xFFFFAA00,
            false, matrix, consumers, Font.DisplayMode.SEE_THROUGH, 0x40000000, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    // --- Line helper ---

    private static void line(BufferBuilder builder, Matrix4f matrix,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        builder.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
    }
}
