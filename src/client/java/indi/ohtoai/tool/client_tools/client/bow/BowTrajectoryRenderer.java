package indi.ohtoai.tool.client_tools.client.bow;

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
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Renders arrow trajectory prediction for bows and crossbows.
 *
 * <p>Features:
 * <ul>
 *   <li>Clean dotted parabolic trajectory (dense sub-sampled points)</li>
 *   <li>Landing marker oriented perpendicular to the hit face</li>
 *   <li>Entity-hit detection — trajectory changes colour at the impact point</li>
 *   <li>Multishot support (3 trajectories at ±10° spread)</li>
 * </ul>
 */
public class BowTrajectoryRenderer {

    // ── Colours ────────────────────────────────────────────────
    private static final int TRAJECTORY_COLOR   = 0xFFAA00; // gold (normal)
    private static final int ENTITY_HIT_COLOR   = 0x33FF33; // green (entity hit)
    private static final int LANDING_COLOR      = 0xFF3300; // red-orange
    private static final int LABEL_COLOR        = 0xFF5500; // orange
    private static final float SIDE_ALPHA       = 0.5f;
    private static final float CENTER_ALPHA     = 0.85f;

    // ── Simulation ─────────────────────────────────────────────
    private static final int   MAX_TICKS        = 200;
    private static final double GRAVITY         = 0.05;
    private static final double AIR_DRAG        = 0.99;
    private static final double WATER_DRAG      = 0.6;
    private static final double MULTISHOT_ANGLE = 10.0;

    // ── Dotted-line detail ─────────────────────────────────────
    /** World-space distance between interpolated points. */
    private static final double SUB_STEP       = 0.2;
    /** Dash length in blocks. */
    private static final double DASH_LEN       = 0.4;
    /** Gap length in blocks. */
    private static final double GAP_LEN        = 0.2;

    private BowTrajectoryRenderer() {}

    // ── Cached trajectory data ─────────────────────────────────
    private static List<List<Vec3>> cachedTrajectories = null;
    private static List<Vec3>       cachedLandings     = null;
    private static List<Direction>  cachedHitFaces     = null;
    private static List<Vec3>       cachedEntityHits   = null;
    private static List<Vec3>       cachedLookDirs      = null;

    // ==================== Tick ====================

    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            clearCache();
            return;
        }

        if (!BowTrajectoryState.isEnabled()) {
            clearCache();
            return;
        }

        Player player = client.player;

        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand  = player.getOffhandItem();

        ItemStack bowItem = ItemStack.EMPTY;
        boolean isMain = true;

        if (isBowOrCrossbow(mainHand)) {
            bowItem = mainHand;
        } else if (isBowOrCrossbow(offHand)) {
            bowItem = offHand;
            isMain = false;
        }

        if (bowItem.isEmpty()) {
            clearCache();
            return;
        }

        boolean isCrossbow = bowItem.getItem() instanceof CrossbowItem;

        double arrowSpeed;
        if (isCrossbow) {
            if (!isCrossbowCharged(bowItem)) { clearCache(); return; }
            arrowSpeed = 3.15;
        } else {
            if (!player.isUsingItem() || !(player.getUseItem().getItem() instanceof BowItem)) {
                clearCache(); return;
            }
            ItemStack useItem = player.getUseItem();
            if ((isMain && useItem != mainHand) || (!isMain && useItem != offHand)) {
                clearCache(); return;
            }
            float charge = Math.min(player.getTicksUsingItem(), 20) / 20.0f;
            arrowSpeed = charge * 3.0;
        }

        int multishotLevel = 0;
        try {
            multishotLevel = EnchantmentHelper.getItemEnchantmentLevel(
                client.level.holderLookup(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.MULTISHOT), bowItem);
        } catch (Exception ignored) {}

        Vec3 baseLook = player.getLookAngle();
        List<Vec3> lookDirs = new ArrayList<>();
        lookDirs.add(baseLook);
        if (multishotLevel > 0) {
            lookDirs.add(rotateYaw(baseLook, -MULTISHOT_ANGLE));
            lookDirs.add(rotateYaw(baseLook, +MULTISHOT_ANGLE));
        }

        // Arrow spawns at player position, eye height - 0.1 (matches vanilla Arrow constructor)
        Vec3 startPos = new Vec3(player.getX(), player.getEyeY() - 0.1, player.getZ());

        Vec3 playerVel = player.getDeltaMovement();
        // Vanilla: Y velocity is only added when the player is off the ground
        double addedY = player.onGround() ? 0.0 : playerVel.y;
        Vec3 effectivePlayerVel = new Vec3(playerVel.x, addedY, playerVel.z);

        boolean inWater = player.isInWater();

        List<List<Vec3>> allTrajectories = new ArrayList<>();
        List<Vec3> allLandings = new ArrayList<>();
        List<Direction> allHitFaces = new ArrayList<>();
        List<Vec3> allEntityHits = new ArrayList<>();

        for (Vec3 lookDir : lookDirs) {
            Vec3 initialVel = effectivePlayerVel.add(
                lookDir.x * arrowSpeed, lookDir.y * arrowSpeed, lookDir.z * arrowSpeed);

            SimulationResult result = simulateTrajectory(
                client.level, client.player, startPos, initialVel, inWater, MAX_TICKS);

            if (result.points != null && !result.points.isEmpty()) {
                allTrajectories.add(result.points);
            }
            allLandings.add(result.landing);
            allHitFaces.add(result.hitFace);
            allEntityHits.add(result.entityHit);
        }

        cachedTrajectories = allTrajectories.isEmpty() ? null : allTrajectories;
        cachedLandings     = allLandings;
        cachedHitFaces     = allHitFaces;
        cachedEntityHits   = allEntityHits;
        cachedLookDirs     = lookDirs;
    }

    // ==================== Render ====================

    public static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (cachedTrajectories == null) return;

        PoseStack poseStack = context.matrixStack();
        if (poseStack == null) return;

        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();

        MultiBufferSource consumers = context.consumers();
        if (consumers == null) return;

        float[] trajRgb   = colorToRGB(TRAJECTORY_COLOR);
        float[] entityRgb = colorToRGB(ENTITY_HIT_COLOR);
        float[] landRgb   = colorToRGB(LANDING_COLOR);

        for (int i = 0; i < cachedTrajectories.size(); i++) {
            List<Vec3> points = cachedTrajectories.get(i);
            Vec3      landing = cachedLandings.size()     > i ? cachedLandings.get(i)     : null;
            Direction hitFace = cachedHitFaces.size()     > i ? cachedHitFaces.get(i)     : null;
            Vec3      entity  = cachedEntityHits.size()   > i ? cachedEntityHits.get(i)   : null;

            boolean isCenter = (i == 0);
            float alpha = isCenter ? CENTER_ALPHA : SIDE_ALPHA;

            if (!points.isEmpty()) {
                renderDottedTrajectory(poseStack, camPos, points, entity,
                    trajRgb, entityRgb, alpha);
            }

            // Entity hit marker
            if (entity != null) {
                renderEntityHitMarker(poseStack, camPos, entity, entityRgb, alpha + 0.15f);
            }

            if (landing != null) {
                renderLandingMarker(poseStack, camPos, landing, hitFace,
                    landRgb, alpha + 0.1f);

                if (isCenter) {
                    double dist = landing.distanceTo(client.player.getEyePosition());
                    renderLandingLabel(poseStack, camPos, camera, client, consumers,
                        landing, dist);
                }
            }
        }
    }

    // ==================== Physics simulation ====================

    private static SimulationResult simulateTrajectory(
            Level level, Player player, Vec3 origin, Vec3 velocity,
            boolean inWater, int maxTicks) {

        List<Vec3> points = new ArrayList<>();
        points.add(origin);

        Vec3 pos = origin;
        Vec3 vel = velocity;
        Vec3 entityHit   = null;
        Vec3 blockHit    = null;
        Direction hitFace = null;

        for (int tick = 0; tick < maxTicks; tick++) {
            // Vanilla order: move first with current velocity,
            // then update velocity for next tick (gravity + drag).
            Vec3 prevPos = pos;
            pos = pos.add(vel);
            points.add(pos);

            // Gravity (only if not in water — vanilla skips gravity underwater)
            if (!inWater) {
                vel = vel.add(0, -GRAVITY, 0);
            }
            // Drag
            vel = vel.scale(inWater ? WATER_DRAG : AIR_DRAG);

            // Void
            if (pos.y < level.getMinBuildHeight() - 10) {
                return new SimulationResult(points, null, null, null);
            }

            // Entity collision (only check if not yet found)
            if (entityHit == null) {
                entityHit = checkEntityCollision(level, player, prevPos, pos);
            }

            // Block collision
            BlockHitResult blockResult = checkBlockCollision(level, prevPos, pos);
            if (blockResult != null && blockResult.getType() != HitResult.Type.MISS) {
                blockHit = blockResult.getLocation();
                hitFace = blockResult.getDirection();
                points.set(points.size() - 1, blockHit);
                return new SimulationResult(points, blockHit, hitFace, entityHit);
            }

            // Unloaded chunk
            BlockPos bp = BlockPos.containing(pos);
            if (!level.isLoaded(bp)) {
                return new SimulationResult(points, null, null, entityHit);
            }
        }

        return new SimulationResult(points, null, null, entityHit);
    }

    /**
     * Checks whether the segment [from → to] intersects any living entity.
     * Excludes the shooting player.
     */
    private static Vec3 checkEntityCollision(Level level, Player shooter, Vec3 from, Vec3 to) {
        // Expand the segment into a thin AABB for broad-phase query
        AABB segmentBox = new AABB(from, to).inflate(0.3);

        Predicate<Entity> filter = e ->
            e != shooter
            && e instanceof net.minecraft.world.entity.LivingEntity
            && e.isAlive();

        List<Entity> candidates = level.getEntities(shooter, segmentBox, filter);

        Vec3 closestHit = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : candidates) {
            AABB box = entity.getBoundingBox().inflate(0.1);
            // Clip the segment against the entity's bounding box
            Vec3 hit = clipSegmentAgainstAABB(from, to, box);
            if (hit != null) {
                double d = from.distanceToSqr(hit);
                if (d < closestDist) {
                    closestDist = d;
                    closestHit = hit;
                }
            }
        }

        return closestHit;
    }

    /**
     * Returns the point where the segment [from → to] first enters the AABB,
     * or null if it doesn't intersect.
     */
    private static Vec3 clipSegmentAgainstAABB(Vec3 from, Vec3 to, AABB box) {
        double tMin = 0.0;
        double tMax = 1.0;

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        // X slab
        if (Math.abs(dx) < 1e-9) {
            if (from.x < box.minX || from.x > box.maxX) return null;
        } else {
            double t1 = (box.minX - from.x) / dx;
            double t2 = (box.maxX - from.x) / dx;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return null;
        }

        // Y slab
        if (Math.abs(dy) < 1e-9) {
            if (from.y < box.minY || from.y > box.maxY) return null;
        } else {
            double t1 = (box.minY - from.y) / dy;
            double t2 = (box.maxY - from.y) / dy;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return null;
        }

        // Z slab
        if (Math.abs(dz) < 1e-9) {
            if (from.z < box.minZ || from.z > box.maxZ) return null;
        } else {
            double t1 = (box.minZ - from.z) / dz;
            double t2 = (box.maxZ - from.z) / dz;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return null;
        }

        return new Vec3(
            from.x + tMin * dx,
            from.y + tMin * dy,
            from.z + tMin * dz);
    }

    private static BlockHitResult checkBlockCollision(Level level, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(
            from, to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            CollisionContext.empty());

        BlockHitResult result = level.clip(ctx);
        if (result != null && result.getType() == HitResult.Type.BLOCK) {
            return result;
        }

        // Fallback: direct block-at-point check
        BlockPos bp = BlockPos.containing(to);
        if (level.isLoaded(bp)) {
            BlockState state = level.getBlockState(bp);
            if (!state.isAir() && state.isCollisionShapeFullBlock(level, bp)) {
                Vec3 faceCenter = Vec3.atCenterOf(bp);
                Vec3 hitPos = new Vec3(
                    clamp(to.x, bp.getX(), bp.getX() + 1),
                    clamp(to.y, bp.getY(), bp.getY() + 1),
                    clamp(to.z, bp.getZ(), bp.getZ() + 1));
                return new BlockHitResult(hitPos,
                    Direction.getNearest(
                        to.x - faceCenter.x,
                        to.y - faceCenter.y,
                        to.z - faceCenter.z),
                    bp, false);
            }
        }

        return null;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    // ==================== Trajectory rendering ====================

    /**
     * Renders the trajectory as a clean dotted line.  When an entity-hit
     * point is provided, the entire line is drawn in the entity colour.
     */
    private static void renderDottedTrajectory(PoseStack poseStack, Vec3 camPos,
                                                List<Vec3> rawPoints,
                                                Vec3 entityHit,
                                                float[] normalRgb, float[] entityRgb,
                                                float alpha) {
        if (rawPoints.size() < 2) return;

        List<Vec3> dense = subdivide(rawPoints, SUB_STEP);
        if (dense.size() < 2) return;

        // Entire trajectory uses entity colour when an entity would be hit
        boolean hitEntity = entityHit != null;
        float r = hitEntity ? entityRgb[0] : normalRgb[0];
        float g = hitEntity ? entityRgb[1] : normalRgb[1];
        float b = hitEntity ? entityRgb[2] : normalRgb[2];

        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(1.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(
            VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // Dotted pattern: accumulate distance, toggle draw
        double accum = 0.0;
        double cycle = DASH_LEN + GAP_LEN;

        for (int i = 1; i < dense.size(); i++) {
            Vec3 p0 = dense.get(i - 1);
            Vec3 p1 = dense.get(i);

            double segLen = p1.distanceTo(p0);
            accum += segLen;

            if (accum >= cycle) {
                accum -= cycle;
            }
            // Dash while accum < DASH_LEN, gap otherwise
            if (accum >= DASH_LEN) continue;

            float x0 = (float)(p0.x - camPos.x);
            float y0 = (float)(p0.y - camPos.y);
            float z0 = (float)(p0.z - camPos.z);
            float x1 = (float)(p1.x - camPos.x);
            float y1 = (float)(p1.y - camPos.y);
            float z1 = (float)(p1.z - camPos.z);

            line(builder, matrix, x0, y0, z0, x1, y1, z1, r, g, b, alpha);
        }

        safeDraw(builder);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static List<Vec3> subdivide(List<Vec3> raw, double maxStep) {
        List<Vec3> out = new ArrayList<>();
        out.add(raw.get(0));
        for (int i = 1; i < raw.size(); i++) {
            Vec3 prev = raw.get(i - 1);
            Vec3 curr = raw.get(i);
            double dist = curr.distanceTo(prev);
            if (dist <= maxStep) {
                out.add(curr);
            } else {
                int steps = (int) Math.ceil(dist / maxStep);
                for (int s = 1; s <= steps; s++) {
                    double t = (double) s / steps;
                    out.add(new Vec3(
                        prev.x + (curr.x - prev.x) * t,
                        prev.y + (curr.y - prev.y) * t,
                        prev.z + (curr.z - prev.z) * t));
                }
            }
        }
        return out;
    }

    // ==================== Landing marker ====================

    /**
     * Renders a landing marker oriented perpendicular to the hit face.
     * <ul>
     *   <li>Hit the ground (UP/DOWN face) → horizontal crosshair + beacon</li>
     *   <li>Hit a wall (horizontal face) → vertical crosshair facing outward</li>
     * </ul>
     */
    // ==================== Entity hit marker ====================

    /**
     * Small diamond marker at the entity impact point.
     */
    private static void renderEntityHitMarker(PoseStack poseStack, Vec3 camPos,
                                              Vec3 hit, float[] rgb, float alpha) {
        float x = (float)(hit.x - camPos.x);
        float y = (float)(hit.y - camPos.y);
        float z = (float)(hit.z - camPos.z);
        float s = 0.25f;
        float cr = rgb[0], cg = rgb[1], cb = rgb[2];

        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.5f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(
            VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // 3D diamond / octahedron shape
        line(builder, matrix, x - s, y, z, x, y, z - s, cr, cg, cb, alpha);
        line(builder, matrix, x, y, z - s, x + s, y, z, cr, cg, cb, alpha);
        line(builder, matrix, x + s, y, z, x, y, z + s, cr, cg, cb, alpha);
        line(builder, matrix, x, y, z + s, x - s, y, z, cr, cg, cb, alpha);
        // Top/bottom connections
        line(builder, matrix, x - s, y, z, x, y + s, z, cr, cg, cb, alpha);
        line(builder, matrix, x + s, y, z, x, y + s, z, cr, cg, cb, alpha);
        line(builder, matrix, x, y, z - s, x, y + s, z, cr, cg, cb, alpha);
        line(builder, matrix, x, y, z + s, x, y + s, z, cr, cg, cb, alpha);
        line(builder, matrix, x - s, y, z, x, y - s, z, cr, cg, cb, alpha);
        line(builder, matrix, x + s, y, z, x, y - s, z, cr, cg, cb, alpha);
        line(builder, matrix, x, y, z - s, x, y - s, z, cr, cg, cb, alpha);
        line(builder, matrix, x, y, z + s, x, y - s, z, cr, cg, cb, alpha);

        safeDraw(builder);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
        poseStack.popPose();
    }

    private static void renderLandingMarker(PoseStack poseStack, Vec3 camPos,
                                            Vec3 landing, Direction hitFace,
                                            float[] rgb, float alpha) {
        float cr = rgb[0], cg = rgb[1], cb = rgb[2];
        float size = 0.3f;
        float beaconLen = 5.0f;

        // Offset slightly outward from the hit face to prevent z-fighting
        float ox = 0, oy = 0, oz = 0;
        if (hitFace != null) {
            ox = (float) hitFace.getStepX() * 0.05f;
            oy = (float) hitFace.getStepY() * 0.05f;
            oz = (float) hitFace.getStepZ() * 0.05f;
        }

        float lx = (float)(landing.x - camPos.x) + ox;
        float ly = (float)(landing.y - camPos.y) + oy;
        float lz = (float)(landing.z - camPos.z) + oz;

        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.5f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(
            VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // Determine which plane the crosshair lives in.
        // The crosshair plane is perpendicular to the hit face normal.
        boolean isVertical = hitFace != null && hitFace.getAxis() != Direction.Axis.Y;

        if (isVertical) {
            // ── Vertical crosshair (wall hit) ──────────────
            // Render in the plane perpendicular to the wall normal.
            // X-axis varies in the horizontal plane orthogonal to the normal.

            Direction.Axis wallAxis = hitFace.getAxis();

            // One axis is always Y (up), the other is the remaining horizontal axis
            if (wallAxis == Direction.Axis.X) {
                // Wall faces east/west → crosshair in YZ plane
                // Vertical line
                line(builder, matrix, lx, ly - size, lz, lx, ly + size, lz, cr, cg, cb, alpha);
                // Horizontal line (along Z)
                line(builder, matrix, lx, ly, lz - size, lx, ly, lz + size, cr, cg, cb, alpha);
                // Diagonals
                line(builder, matrix, lx, ly - size, lz - size, lx, ly + size, lz + size, cr, cg, cb, alpha);
                line(builder, matrix, lx, ly + size, lz - size, lx, ly - size, lz + size, cr, cg, cb, alpha);
                // Ring in YZ plane
                int segs = 10;
                for (int i = 0; i < segs; i++) {
                    double a1 = Math.PI * 2 * i / segs;
                    double a2 = Math.PI * 2 * (i + 1) / segs;
                    line(builder, matrix,
                        lx, ly + (float)(Math.cos(a1) * size), lz + (float)(Math.sin(a1) * size),
                        lx, ly + (float)(Math.cos(a2) * size), lz + (float)(Math.sin(a2) * size),
                        cr, cg, cb, alpha);
                }
                // Beacon points outward (along face normal)
                float bx = (float) hitFace.getStepX() * beaconLen;
                line(builder, matrix, lx, ly, lz, lx + bx, ly, lz, cr, cg, cb, alpha * 0.5f);
            } else {
                // Wall faces north/south → crosshair in XY plane
                // Vertical line
                line(builder, matrix, lx, ly - size, lz, lx, ly + size, lz, cr, cg, cb, alpha);
                // Horizontal line (along X)
                line(builder, matrix, lx - size, ly, lz, lx + size, ly, lz, cr, cg, cb, alpha);
                // Diagonals
                line(builder, matrix, lx - size, ly - size, lz, lx + size, ly + size, lz, cr, cg, cb, alpha);
                line(builder, matrix, lx + size, ly - size, lz, lx - size, ly + size, lz, cr, cg, cb, alpha);
                // Ring in XY plane
                int segs = 10;
                for (int i = 0; i < segs; i++) {
                    double a1 = Math.PI * 2 * i / segs;
                    double a2 = Math.PI * 2 * (i + 1) / segs;
                    line(builder, matrix,
                        lx + (float)(Math.cos(a1) * size), ly + (float)(Math.sin(a1) * size), lz,
                        lx + (float)(Math.cos(a2) * size), ly + (float)(Math.sin(a2) * size), lz,
                        cr, cg, cb, alpha);
                }
                // Beacon points outward
                float bz = (float) hitFace.getStepZ() * beaconLen;
                line(builder, matrix, lx, ly, lz, lx, ly, lz + bz, cr, cg, cb, alpha * 0.5f);
            }
        } else {
            // ── Horizontal crosshair (ground / ceiling hit) ──
            // X extent
            line(builder, matrix, lx - size, ly, lz, lx + size, ly, lz, cr, cg, cb, alpha);
            // Z extent
            line(builder, matrix, lx, ly, lz - size, lx, ly, lz + size, cr, cg, cb, alpha);
            // Y extent
            line(builder, matrix, lx, ly - size, lz, lx, ly + size, lz, cr, cg, cb, alpha);
            // Diagonals in XZ
            line(builder, matrix, lx - size, ly, lz - size, lx + size, ly, lz + size, cr, cg, cb, alpha);
            line(builder, matrix, lx + size, ly, lz - size, lx - size, ly, lz + size, cr, cg, cb, alpha);
            // Ring
            int segs = 10;
            for (int i = 0; i < segs; i++) {
                double a1 = Math.PI * 2 * i / segs;
                double a2 = Math.PI * 2 * (i + 1) / segs;
                line(builder, matrix,
                    lx + (float)(Math.cos(a1) * size), ly, lz + (float)(Math.sin(a1) * size),
                    lx + (float)(Math.cos(a2) * size), ly, lz + (float)(Math.sin(a2) * size),
                    cr, cg, cb, alpha);
            }
            // Vertical beacon
            line(builder, matrix, lx, ly, lz, lx, ly + beaconLen, lz, cr, cg, cb, alpha * 0.5f);
        }

        safeDraw(builder);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
        poseStack.popPose();
    }

    // ==================== Landing label ====================

    private static void renderLandingLabel(PoseStack poseStack, Vec3 camPos,
                                           Camera camera, Minecraft client,
                                           MultiBufferSource consumers,
                                           Vec3 landing, double distance) {
        String text = String.format("%.1fm", distance);

        poseStack.pushPose();
        poseStack.translate(
            landing.x - camPos.x,
            landing.y + 1.2 - camPos.y,
            landing.z - camPos.z);
        poseStack.mulPose(camera.rotation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);

        float textWidth = client.font.width(text);
        Matrix4f matrix = poseStack.last().pose();

        client.font.drawInBatch(
            text,
            -textWidth / 2.0f, 0.0f,
            LABEL_COLOR | 0xFF000000,
            false,
            matrix,
            consumers,
            Font.DisplayMode.SEE_THROUGH,
            0x40000000,
            LightTexture.FULL_BRIGHT);

        poseStack.popPose();
    }

    // ==================== Helpers ====================

    private static boolean isBowOrCrossbow(ItemStack stack) {
        return stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem;
    }

    private static boolean isCrossbowCharged(ItemStack stack) {
        return stack.has(DataComponents.CHARGED_PROJECTILES);
    }

    static Vec3 rotateYaw(Vec3 dir, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vec3(
            dir.x * cos - dir.z * sin,
            dir.y,
            dir.x * sin + dir.z * cos);
    }

    private static float[] colorToRGB(int color) {
        return new float[] {
            ((color >> 16) & 0xFF) / 255.0f,
            ((color >> 8) & 0xFF) / 255.0f,
            (color & 0xFF) / 255.0f
        };
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

    static void clearCache() {
        cachedTrajectories = null;
        cachedLandings     = null;
        cachedHitFaces     = null;
        cachedEntityHits   = null;
        cachedLookDirs     = null;
    }

    // ==================== Getters for status ====================

    static double getCachedCharge() { return cachedTrajectories != null ? 1.0 : 0.0; }
    static boolean isCachedBow() { return true; }
    static List<Vec3> getCachedLookDirs() { return cachedLookDirs; }

    // ==================== Record ====================

    private static class SimulationResult {
        final List<Vec3> points;
        final Vec3       landing;
        final Direction  hitFace;
        final Vec3       entityHit;

        SimulationResult(List<Vec3> points, Vec3 landing, Direction hitFace, Vec3 entityHit) {
            this.points    = points;
            this.landing   = landing;
            this.hitFace   = hitFace;
            this.entityHit = entityHit;
        }
    }
}
