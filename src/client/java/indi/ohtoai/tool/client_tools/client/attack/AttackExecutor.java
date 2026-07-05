package indi.ohtoai.tool.client_tools.client.attack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Tick-driven executor that automatically attacks a target entity on a configurable interval.
 *
 * <p>On {@link #start(String, int)}, the executor stores the entity selector and attack
 * interval. Each tick, it re-resolves the selector to find a valid target, then sends
 * an attack packet when the interval has elapsed.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #start(String, int)} — stores selector and interval, transitions to ARMED</li>
 *   <li>{@link #tick(Minecraft)} — re-resolves target, attacks on interval</li>
 *   <li>{@link #stop()} — transitions to IDLE</li>
 * </ol>
 */
public class AttackExecutor {

    private enum State {
        IDLE, ARMED
    }

    // --- Singleton ---

    private static AttackExecutor instance;

    public static AttackExecutor getInstance() {
        if (instance == null) instance = new AttackExecutor();
        return instance;
    }

    private AttackExecutor() {}

    // --- State fields ---

    private State state = State.IDLE;

    /** The entity selector string (e.g. "@e[distance=..5,limit=1]", "PlayerName"). */
    private String selector = "";

    /** Attack interval in ticks (≥ 1). */
    private int intervalTicks = 10;

    /** Ticks elapsed since the last attack. */
    private int ticksSinceLastAttack = 0;

    /** The currently resolved target entity. */
    private Entity targetEntity;

    /** Cached display name for chat messages. */
    private String targetDisplayName = "";

    /** Error message for the last failed operation. */
    private String errorMessage = "";

    /** Total attack count since start. */
    private int totalAttacks = 0;

    // --- Public API ---

    /**
     * Starts auto-attacking with the given entity selector and interval.
     *
     * @param selector      entity selector string (e.g. "@e[distance=..5,limit=1]", "PlayerName")
     * @param intervalTicks attack interval in ticks (≥ 1)
     * @return null on success, or an error message key suffix on failure
     */
    public String start(String selector, int intervalTicks) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "not_connected";
        }
        if (state == State.ARMED) {
            return "already_running";
        }
        if (intervalTicks < 1) {
            return "interval_invalid";
        }

        this.selector = selector;
        this.intervalTicks = intervalTicks;
        this.ticksSinceLastAttack = 0;
        this.targetEntity = null;
        this.targetDisplayName = "";
        this.totalAttacks = 0;
        this.errorMessage = "";

        // Try to resolve the target immediately for validation
        try {
            Entity target = resolveSelector(client, selector);
            if (target == null) {
                // Don't fail — the target may appear later. Arm anyway.
                this.targetEntity = null;
                this.targetDisplayName = selector;
            } else if (target == client.player) {
                return "self_target";
            } else {
                this.targetEntity = target;
                this.targetDisplayName = target.getName().getString();
            }
        } catch (IllegalArgumentException e) {
            return "invalid_selector";
        }

        state = State.ARMED;
        return null;
    }

    /**
     * Stops auto-attacking.
     *
     * @return null on success, or an error message key suffix if not attacking
     */
    public String stop() {
        if (state != State.ARMED) {
            return "not_running";
        }
        state = State.IDLE;
        targetEntity = null;
        targetDisplayName = "";
        selector = "";
        ticksSinceLastAttack = 0;
        totalAttacks = 0;
        return null;
    }

    public boolean isActive() {
        return state == State.ARMED;
    }

    public String getSelector() { return selector; }
    public int getIntervalTicks() { return intervalTicks; }
    public String getTargetDisplayName() { return targetDisplayName; }
    public int getTotalAttacks() { return totalAttacks; }
    public String getErrorMessage() { return errorMessage; }

    // --- Tick ---

    /**
     * Called every client tick. When armed, re-resolves the target and attacks
     * when the interval has elapsed.
     */
    public void tick(Minecraft client) {
        if (state != State.ARMED) return;
        if (client.player == null || client.level == null) {
            stop();
            return;
        }

        ticksSinceLastAttack++;

        // Re-resolve the target each tick for dynamic re-targeting
        try {
            Entity resolved = resolveSelector(client, selector);
            if (resolved == null || resolved == client.player || resolved.isRemoved() || !resolved.isAlive()) {
                targetEntity = null;
                // Keep ticking — target may appear/respawn later
                return;
            }
            if (resolved.level() != client.level) {
                targetEntity = null;
                return;
            }
            targetEntity = resolved;
            targetDisplayName = resolved.getName().getString();
        } catch (IllegalArgumentException e) {
            errorMessage = "Invalid selector: " + selector;
            state = State.IDLE;
            return;
        }

        // Check if it's time to attack
        if (ticksSinceLastAttack >= intervalTicks) {
            ticksSinceLastAttack = 0;

            if (targetEntity != null && targetEntity.isAlive() && !targetEntity.isRemoved()) {
                // Perform the attack
                client.gameMode.attack(client.player, targetEntity);
                totalAttacks++;
            }
        }
    }

    // --- Entity selector resolution ---

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
        // Also search non-player entities by name
        for (Entity e : getSelectableEntities(client.level, client.player)) {
            if (e.getName().getString().equalsIgnoreCase(selector)) return e;
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
}
