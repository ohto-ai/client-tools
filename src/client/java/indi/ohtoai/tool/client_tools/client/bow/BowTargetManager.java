package indi.ohtoai.tool.client_tools.client.bow;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Predicate;

/**
 * Manages bow/crossbow auto-aim targeting.
 *
 * <h3>State machine</h3>
 * <pre>
 *   IDLE  →  ARMED      (/cbow target auto | selector | name)
 *   ARMED →  AIMING     (player starts drawing bow; for auto: raycast hits entity)
 *   AIMING → ARMED      (player releases bow — target config kept for next use)
 *   AIMING → IDLE       (target dies / item switched / /cbow target stop)
 *   ARMED →  IDLE       (/cbow target stop)
 * </pre>
 *
 * <p>The target configuration persists across bow draw/release cycles.
 * In {@code auto} mode the raycast runs each time the bow is drawn; the
 * player keeps normal mouse control if no entity is in their sights.
 */
public class BowTargetManager {

    /** IDLE: no target configured.  ARMED: target configured, waiting for bow draw.  AIMING: actively controlling aim. */
    private enum State { IDLE, ARMED, AIMING }

    // ── Singleton ────────────────────────────────────────────────
    private static BowTargetManager instance;

    public static BowTargetManager getInstance() {
        if (instance == null) instance = new BowTargetManager();
        return instance;
    }

    private BowTargetManager() {}

    // ── State fields ─────────────────────────────────────────────
    private State state = State.IDLE;
    /** The entity we are targeting (resolved at command-time for manual, at draw-time for auto). */
    private Entity targetEntity;
    /** Cached display name for chat messages. */
    private String targetDisplayName = "";
    /** True when the user ran {@code /cbow target auto}. */
    private boolean autoMode = false;
    /** Entity selector / player name stored for manual mode (used for status display). */
    private String manualSelector = "";
    /** When true, compensate for target velocity (lead the target). */
    private boolean velocityPredict = false;

    /** Last-computed desired yaw (degrees), read by the view-rotation mixin. */
    private float desiredYaw;
    /** Last-computed desired pitch (degrees), read by the view-rotation mixin. */
    private float desiredPitch;

    // ── Physics constants (matching BowTrajectoryRenderer) ────────
    private static final double GRAVITY = 0.05;
    private static final double CROSSBOW_SPEED = 3.15;

    // ── Public API ───────────────────────────────────────────────

    /**
     * Returns {@code true} when the aim controller is actively overriding
     * the player's rotation.  Used by the mixin to suppress mouse input.
     */
    public boolean isActive() {
        return state == State.AIMING;
    }

    public Entity getTarget() {
        return targetEntity;
    }

    public String getTargetName() {
        if (state == State.IDLE) return "";
        if (autoMode && targetEntity != null) return targetEntity.getName().getString();
        if (autoMode) return "auto";
        return manualSelector;
    }

    public boolean isAutoMode() {
        return autoMode;
    }

    /** Last-computed yaw for the view-rotation mixin to read. */
    public float getDesiredYaw() { return desiredYaw; }
    /** Last-computed pitch for the view-rotation mixin to read. */
    public float getDesiredPitch() { return desiredPitch; }
    /** Whether velocity prediction (leading) is enabled. */
    public boolean isVelocityPredictEnabled() { return velocityPredict; }
    /** Toggle velocity prediction on/off, returning the new state. */
    public boolean toggleVelocityPredict() {
        velocityPredict = !velocityPredict;
        return velocityPredict;
    }

    // ── Command entry points ─────────────────────────────────────

    /**
     * Arms auto-targeting mode.  The actual raycast happens each time the
     * player starts drawing the bow.
     *
     * @return null on success, or an error message key suffix on failure
     */
    public String startAuto(Minecraft client) {
        if (client.player == null || client.level == null) return "not_connected";
        if (state == State.AIMING) return "already_targeting";

        autoMode = true;
        manualSelector = "";
        targetEntity = null;
        targetDisplayName = "";
        state = State.ARMED;
        return null;
    }

    /**
     * Arms manual targeting mode for the given entity selector or player name.
     * The entity is resolved immediately; if it can't be found now the arming
     * fails.
     *
     * @return null on success, or an error message key suffix on failure
     */
    public String startManual(Minecraft client, String selector) {
        if (client.player == null || client.level == null) return "not_connected";
        if (state == State.AIMING) return "already_targeting";

        Entity target;
        try {
            target = resolveSelector(client, selector);
        } catch (IllegalArgumentException e) {
            return "invalid_selector";
        }

        if (target == null) return "no_target";
        if (target == client.player) return "self_target";

        autoMode = false;
        manualSelector = selector;
        targetEntity = target;
        targetDisplayName = target.getName().getString();
        state = State.ARMED;
        return null;
    }

    /** Fully clears the target configuration and returns to IDLE. */
    public void stop() {
        state = State.IDLE;
        targetEntity = null;
        targetDisplayName = "";
        autoMode = false;
        manualSelector = "";
    }

    // ── Tick ─────────────────────────────────────────────────────

    public void tick(Minecraft client) {
        if (state == State.IDLE) return;
        if (client.player == null || client.level == null) { stop(); return; }

        ItemStack bowItem = findBowItem(client.player);
        boolean isDrawing = isPlayerDrawingBow(client.player, bowItem);

        switch (state) {
            case ARMED -> tickArmed(client, bowItem, isDrawing);
            case AIMING -> tickAiming(client, bowItem, isDrawing);
            default -> {}
        }
    }

    // ── ARMED state: wait for bow draw ───────────────────────────

    private void tickArmed(Minecraft client, ItemStack bowItem, boolean isDrawing) {
        if (!isDrawing) return; // not drawing yet — nothing to do

        // Bow draw just started (or is ongoing) — try to activate
        if (autoMode) {
            // Raycast from the player's current look direction
            Entity hit = autoRaycast(client);
            if (hit == null) {
                // No entity in sight — player keeps normal mouse control.
                // We stay ARMED so the raycast retries every tick while the
                // bow is drawn (in case the player looks at an entity later).
                return;
            }
            targetEntity = hit;
            targetDisplayName = hit.getName().getString();
        }

        // Validate the target is still alive
        if (targetEntity == null || targetEntity.isRemoved() || !targetEntity.isAlive()) {
            stopWithMessage(client, "target_died");
            return;
        }
        if (targetEntity.level() != client.level) {
            stop();
            return;
        }

        // Transition to AIMING
        state = State.AIMING;
        client.player.displayClientMessage(
            Component.translatable(autoMode
                ? "client-tools.cbow.target.locked_auto"
                : "client-tools.cbow.target.locked",
                targetDisplayName), false);

        // Apply aim immediately this tick
        applyAim(client, bowItem);
    }

    // ── AIMING state: actively control the player's rotation ─────

    private void tickAiming(Minecraft client, ItemStack bowItem, boolean isDrawing) {
        // Check if player stopped drawing the bow
        if (!isDrawing) {
            // Bow released — go back to ARMED (keep target config)
            state = State.ARMED;
            return;
        }

        // Crossbow: check if still charged
        if (bowItem.getItem() instanceof CrossbowItem && !isCrossbowCharged(bowItem)) {
            state = State.ARMED;
            return;
        }

        // Target died or left
        if (targetEntity == null || targetEntity.isRemoved() || !targetEntity.isAlive()) {
            stopWithMessage(client, "target_died");
            return;
        }
        if (targetEntity.level() != client.level) {
            stop();
            return;
        }

        applyAim(client, bowItem);
    }

    // ── Aim application ──────────────────────────────────────────

    private void applyAim(Minecraft client, ItemStack bowItem) {
        Vec3 spawnPos = getArrowSpawnPos(client.player);
        Vec3 targetPos = getTargetAimPoint(targetEntity);
        double arrowSpeed = getArrowSpeed(client.player, bowItem);

        // ── Velocity prediction (lead the target) ──────────────
        if (velocityPredict) {
            Vec3 targetVel = targetEntity.getDeltaMovement();
            if (targetVel.lengthSqr() > 0.0001) {
                // Estimate flight time from direct distance and speed,
                // with a drag fudge factor.  The iterative refinement
                // in calculateAim will correct any inaccuracy.
                double dist = spawnPos.distanceTo(targetPos);
                double estFlightTime = arrowSpeed > 0.01
                    ? dist / (arrowSpeed * 0.93)  // 0.93 ≈ average drag
                    : 0.0;
                targetPos = targetPos.add(
                    targetVel.x * estFlightTime,
                    targetVel.y * estFlightTime,
                    targetVel.z * estFlightTime);
            }
        }

        double[] aim = calculateAim(spawnPos, targetPos, arrowSpeed);
        float yaw = (float) aim[0];
        float pitch = (float) aim[1];

        // Store for the view-rotation mixins — these are the
        // authoritative values returned by getViewYRot/getViewXRot
        // while targeting is active.
        this.desiredYaw = yaw;
        this.desiredPitch = pitch;

        // Also set entity fields as a fallback (belt + suspenders)
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        client.player.yRotO = yaw;
        client.player.xRotO = pitch;
        client.player.yHeadRot = yaw;
        client.player.yHeadRotO = yaw;
        client.player.yBodyRot = yaw;
    }

    // ── Aim calculation ──────────────────────────────────────────

    /**
     * Computes the yaw/pitch to hit the target, using iterative
     * trajectory simulation starting from the direct line-of-sight
     * angle (where the player is already looking).
     *
     * <p>On each iteration the arrow trajectory is simulated with full
     * vanilla physics (gravity = 0.05, drag = 0.99).  The vertical
     * error at the target's horizontal distance is measured and the
     * pitch is adjusted upward to compensate — converging within a
     * few iterations.
     *
     * @return {@code double[]{yaw, pitch}} in degrees
     */
    static double[] calculateAim(Vec3 spawnPos, Vec3 targetPos, double arrowSpeed) {
        double dx = targetPos.x - spawnPos.x;
        double dy = targetPos.y - spawnPos.y;
        double dz = targetPos.z - spawnPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // ── Yaw is exact (no lateral forces) ──────────────────
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));

        // ── Iterative pitch refinement ────────────────────────
        // Start from direct line-of-sight pitch.
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontalDist));

        if (arrowSpeed > 0.01 && horizontalDist > 0.01) {
            for (int iter = 0; iter < 8; iter++) {
                double simY = simulateYAtDistance(spawnPos, yaw, pitch,
                    arrowSpeed, horizontalDist);
                if (Double.isNaN(simY)) break;

                double yError = targetPos.y - simY;
                if (Math.abs(yError) < 0.005) break;

                // Finite-difference sensitivity: how many blocks does
                // the simulated Y move per degree of pitch change?
                // This correctly handles cos(pitch) reducing horizontal
                // speed at steep angles (the old atan2 approximation fails
                // at |pitch| > 45°).
                double probePitch = pitch + 0.5;
                double simY2 = simulateYAtDistance(spawnPos, yaw, probePitch,
                    arrowSpeed, horizontalDist);

                double correction;
                if (Double.isNaN(simY2)) {
                    // Probe failed — fall back to the rough linear estimate.
                    correction = Math.toDegrees(Math.atan2(yError, horizontalDist));
                } else {
                    double dY_dDeg = (simY2 - simY) / 0.5; // d(simY)/d(pitch), always < 0
                    if (Math.abs(dY_dDeg) < 1e-6) break;
                    // Newton:  pitch -= f / f'   where f = simY - targetY
                    // yError = targetY - simY = -f   →   correction = -yError / f'
                    correction = -yError / dY_dDeg;
                    // Clamp to prevent wild overshoot on near-vertical shots
                    correction = Math.max(-30.0, Math.min(30.0, correction));
                }

                pitch -= correction;
                pitch = Math.max(-89.5, Math.min(89.5, pitch));
            }
        }

        return new double[]{yaw, pitch};
    }

    /**
     * Simulates the arrow trajectory with vanilla physics and returns
     * the Y coordinate when the arrow reaches {@code targetHorizDist}
     * horizontal distance from the spawn point.
     *
     * @return the interpolated Y, or {@link Double#NaN} if the arrow
     *         never reaches the target distance within 200 ticks
     */
    private static double simulateYAtDistance(Vec3 start, double yawDeg, double pitchDeg,
                                               double speed, double targetHorizDist) {
        double yawRad = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);

        // Velocity components matching vanilla arrow spawn
        double vx = -Math.sin(yawRad) * Math.cos(pitchRad) * speed;
        double vy = -Math.sin(pitchRad) * speed;
        double vz = Math.cos(yawRad) * Math.cos(pitchRad) * speed;

        double x = start.x, y = start.y, z = start.z;
        double velX = vx, velY = vy, velZ = vz;
        double startX = start.x, startZ = start.z;

        // Previous position (for interpolation)
        double prevX = x, prevY = y, prevZ = z;

        for (int tick = 0; tick < 200; tick++) {
            prevX = x; prevY = y; prevZ = z;
            x += velX;
            y += velY;
            z += velZ;

            double curHorizDist = Math.sqrt(
                (x - startX) * (x - startX) + (z - startZ) * (z - startZ));

            if (curHorizDist >= targetHorizDist) {
                // Interpolate Y between previous and current position
                double prevHDist = Math.sqrt(
                    (prevX - startX) * (prevX - startX) + (prevZ - startZ) * (prevZ - startZ));
                double range = curHorizDist - prevHDist;
                if (range < 1e-9) return y;
                double t = (targetHorizDist - prevHDist) / range;
                return prevY + t * (y - prevY);
            }

            // Gravity (applied after move in vanilla; matches BowTrajectoryRenderer)
            velY -= GRAVITY;
            // Drag
            velX *= 0.99;
            velY *= 0.99;
            velZ *= 0.99;
        }

        return Double.NaN;
    }

    // ── Bow state helpers ────────────────────────────────────────

    /**
     * Returns true if the player is actively drawing a bow (or holding a
     * charged crossbow).
     */
    private static boolean isPlayerDrawingBow(Player player, ItemStack bowItem) {
        if (bowItem.isEmpty()) return false;
        if (bowItem.getItem() instanceof CrossbowItem) {
            return isCrossbowCharged(bowItem);
        }
        return player.isUsingItem() && player.getUseItem() == bowItem;
    }

    static ItemStack findBowItem(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        if (isBowOrCrossbow(mainHand)) return mainHand;
        if (isBowOrCrossbow(offHand)) return offHand;
        return ItemStack.EMPTY;
    }

    static boolean isBowOrCrossbow(ItemStack stack) {
        return stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem;
    }

    static boolean isCrossbowCharged(ItemStack stack) {
        return stack.has(DataComponents.CHARGED_PROJECTILES);
    }

    // ── Arrow physics helpers ────────────────────────────────────

    static Vec3 getArrowSpawnPos(Player player) {
        return new Vec3(player.getX(), player.getEyeY() - 0.1, player.getZ());
    }

    static Vec3 getTargetAimPoint(Entity target) {
        if (target instanceof LivingEntity living) {
            return living.getEyePosition();
        }
        return target.getBoundingBox().getCenter();
    }

    static double getArrowSpeed(Player player, ItemStack bowItem) {
        if (bowItem.getItem() instanceof CrossbowItem) {
            return CROSSBOW_SPEED;
        }
        float charge = Math.min(player.getTicksUsingItem(), 20) / 20.0f;
        return charge * 3.0;
    }

    // ── Auto-raycast ─────────────────────────────────────────────

    /**
     * Raycasts from the player's eyes along their look direction,
     * returning the first living entity hit.  Max range: 100 blocks.
     */
    private Entity autoRaycast(Minecraft client) {
        Player player = client.player;
        Vec3 from = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double maxDist = 100.0;
        Vec3 to = from.add(look.x * maxDist, look.y * maxDist, look.z * maxDist);

        AABB segmentBox = new AABB(from, to).inflate(0.3);
        Predicate<Entity> filter = e ->
            e instanceof LivingEntity
            && e != player
            && e.isAlive();

        List<Entity> candidates = client.level.getEntities(player, segmentBox, filter);
        Entity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : candidates) {
            AABB box = entity.getBoundingBox().inflate(0.1);
            Vec3 hit = clipSegmentAgainstAABB(from, to, box);
            if (hit != null) {
                double dist = from.distanceToSqr(hit);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = entity;
                }
            }
        }
        return closest;
    }

    /**
     * Returns the point where segment [from → to] first enters the AABB,
     * or null if no intersection.
     */
    private static Vec3 clipSegmentAgainstAABB(Vec3 from, Vec3 to, AABB box) {
        double tMin = 0.0, tMax = 1.0;
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        // X slab
        if (Math.abs(dx) < 1e-9) {
            if (from.x < box.minX || from.x > box.maxX) return null;
        } else {
            double t1 = (box.minX - from.x) / dx;
            double t2 = (box.maxX - from.x) / dx;
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
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
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
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
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return null;
        }

        return new Vec3(from.x + tMin * dx, from.y + tMin * dy, from.z + tMin * dz);
    }

    // ── Entity selector resolution ───────────────────────────────

    /**
     * Resolves a selector string or player name to a single entity.
     *
     * <p>Supported: {@code @p}, {@code @a} (nearest), {@code @r} (random),
     * {@code @e[...]} (type, distance, sort, name, tag), or a plain player name.
     */
    private Entity resolveSelector(Minecraft client, String selector) {
        if (selector.startsWith("@")) return resolveAtSelector(client, selector);
        // Plain player name
        for (Player p : client.level.players()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(selector)) return p;
        }
        return null;
    }

    private Entity resolveAtSelector(Minecraft client, String selector) {
        switch (selector) {
            case "@p", "@a" -> { return findNearestPlayer(client); }
            case "@r" -> { return findRandomPlayer(client); }
        }
        if (selector.startsWith("@e[")) return resolveEntitySelector(client, selector);
        throw new IllegalArgumentException("Unsupported selector: " + selector);
    }

    private Entity findNearestPlayer(Minecraft client) {
        return client.level.players().stream()
            .filter(p -> p != client.player)
            .min(Comparator.comparingDouble(p -> p.distanceToSqr(client.player)))
            .orElse(null);
    }

    private Entity findRandomPlayer(Minecraft client) {
        List<? extends Player> players = client.level.players().stream()
            .filter(p -> p != client.player).toList();
        if (players.isEmpty()) return null;
        return players.get(new Random().nextInt(players.size()));
    }

    private Entity resolveEntitySelector(Minecraft client, String selector) {
        int start = selector.indexOf('[');
        int end = selector.lastIndexOf(']');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Malformed: " + selector);

        Map<String, String> params = parseSelectorParams(selector.substring(start + 1, end));
        double maxDistance = Double.MAX_VALUE;
        ResourceLocation typeFilter = null;
        boolean sortNearest = false;
        String nameFilter = null, tagFilter = null;

        for (var e : params.entrySet()) {
            switch (e.getKey()) {
                case "distance" -> {
                    String v = e.getValue();
                    if (v.startsWith("..")) {
                        try { maxDistance = Double.parseDouble(v.substring(2)); }
                        catch (NumberFormatException ignored) {}
                    }
                }
                case "type" -> {
                    String v = e.getValue();
                    typeFilter = v.contains(":") ? ResourceLocation.parse(v)
                        : ResourceLocation.withDefaultNamespace(v);
                }
                case "sort" -> sortNearest = "nearest".equals(e.getValue());
                case "name" -> nameFilter = e.getValue();
                case "tag" -> tagFilter = e.getValue();
            }
        }

        Vec3 playerPos = client.player.getEyePosition();
        double maxDistSqr = maxDistance * maxDistance;
        List<Entity> candidates = new ArrayList<>();

        for (Entity entity : getSelectableEntities(client.level, client.player)) {
            if (entity == client.player || !entity.isAlive()) continue;
            if (typeFilter != null && !typeFilter.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())))
                continue;
            if (entity.distanceToSqr(playerPos) > maxDistSqr) continue;
            if (nameFilter != null && !entity.getName().getString().equalsIgnoreCase(nameFilter)) continue;
            if (tagFilter != null && !entity.getTags().contains(tagFilter)) continue;
            candidates.add(entity);
        }

        if (candidates.isEmpty()) return null;
        if (sortNearest) candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(playerPos)));
        return candidates.get(0);
    }

    private static Map<String, String> parseSelectorParams(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : body.split(",")) {
            part = part.trim();
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
                    v = v.substring(1, v.length() - 1);
                params.put(k, v);
            }
        }
        return params;
    }

    private static List<Entity> getSelectableEntities(Level level, Player player) {
        double r = 200.0;
        return level.getEntities(player,
            new AABB(player.getX() - r, player.getY() - r, player.getZ() - r,
                     player.getX() + r, player.getY() + r, player.getZ() + r),
            e -> e instanceof LivingEntity && e.isAlive());
    }

    // ── Internal helpers ─────────────────────────────────────────

    private void stopWithMessage(Minecraft client, String key) {
        stop();
        if (client.player != null) {
            client.player.displayClientMessage(
                Component.translatable("client-tools.cbow.target." + key), false);
        }
    }
}
