package indi.ohtoai.tool.client_tools.client.bow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Renders a wireframe bounding-box highlight around the bow auto-aim target.
 *
 * <p>Controlled by {@code /cbow target highlight}.  The highlight colour
 * differs between auto mode (gold) and manual mode (red) so the player
 * can tell the mode at a glance.
 */
public class BowTargetHighlightRenderer {

    private static final int AUTO_COLOR   = 0xFFAA00; // gold
    private static final int MANUAL_COLOR = 0xFF3333; // red

    private BowTargetHighlightRenderer() {}

    public static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;

        BowTargetManager mgr = BowTargetManager.getInstance();
        if (!mgr.isHighlightEnabled()) return;

        Entity target = mgr.getTarget();
        if (target == null || target.isRemoved() || !target.isAlive()) return;

        AABB box = target.getBoundingBox();
        Vec3 camPos = context.camera().getPosition();

        int color = mgr.isAutoMode() ? AUTO_COLOR : MANUAL_COLOR;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float alpha = 0.7f;

        PoseStack poseStack = context.matrixStack();
        if (poseStack == null) return;

        poseStack.pushPose();

        // Translate so we can use local coordinates relative to the box min
        poseStack.translate(box.minX - camPos.x, box.minY - camPos.y, box.minZ - camPos.z);

        float sx = (float)(box.maxX - box.minX);
        float sy = (float)(box.maxY - box.minY);
        float sz = (float)(box.maxZ - box.minZ);

        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(
            VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // 12 edges of the AABB
        // Bottom face (y=0)
        line(builder, matrix, 0, 0, 0, sx, 0, 0, r, g, b, alpha);
        line(builder, matrix, sx, 0, 0, sx, 0, sz, r, g, b, alpha);
        line(builder, matrix, sx, 0, sz, 0, 0, sz, r, g, b, alpha);
        line(builder, matrix, 0, 0, sz, 0, 0, 0, r, g, b, alpha);
        // Top face (y=sy)
        line(builder, matrix, 0, sy, 0, sx, sy, 0, r, g, b, alpha);
        line(builder, matrix, sx, sy, 0, sx, sy, sz, r, g, b, alpha);
        line(builder, matrix, sx, sy, sz, 0, sy, sz, r, g, b, alpha);
        line(builder, matrix, 0, sy, sz, 0, sy, 0, r, g, b, alpha);
        // Vertical edges
        line(builder, matrix, 0, 0, 0, 0, sy, 0, r, g, b, alpha);
        line(builder, matrix, sx, 0, 0, sx, sy, 0, r, g, b, alpha);
        line(builder, matrix, sx, 0, sz, sx, sy, sz, r, g, b, alpha);
        line(builder, matrix, 0, 0, sz, 0, sy, sz, r, g, b, alpha);

        safeDraw(builder);

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

    private static void safeDraw(BufferBuilder builder) {
        try {
            MeshData mesh = builder.build();
            if (mesh != null) {
                BufferUploader.drawWithShader(mesh);
            }
        } catch (Exception ignored) {}
    }
}
