package indi.ohtoai.tool.client_tools.client.follow;

import indi.ohtoai.tool.client_tools.client.sweep.LitematicaIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Tick-driven executor that makes the Litematica selection follow the player.
 *
 * <p>On {@link #start()}, the executor records the player's current position
 * and the selection's reference point (pos1 of the first sub-region), then
 * computes the offset between them. Each tick, it calculates how far the player
 * has moved (in double precision), rounds to integer blocks, compares against
 * the last applied move, and calls {@link LitematicaIntegration#moveSelection}
 * with the integer delta to keep the relative player→selection position constant.
 *
 * <p>Axis locking is supported: when an axis is locked, movement along that
 * axis is ignored, effectively constraining the selection to the unlocked axes.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #start()} — snapshots positions and transitions to FOLLOWING</li>
 *   <li>{@link #tick(Minecraft)} — computes delta and moves the selection</li>
 *   <li>{@link #stop()} — transitions to IDLE</li>
 * </ol>
 */
public class FollowExecutor {

    private enum State {
        IDLE, FOLLOWING
    }

    // --- Singleton ---

    private static FollowExecutor instance;

    public static FollowExecutor getInstance() {
        if (instance == null) instance = new FollowExecutor();
        return instance;
    }

    private FollowExecutor() {}

    // --- State fields ---

    private State state = State.IDLE;

    /** Player position at the moment {@link #start()} was called. */
    private Vec3 referencePlayerPos;

    /** Selection reference point (pos1 of the first sub-region) at start time. */
    private Vec3 referenceSelectionPos;

    /** Accumulated integer moves applied since start. */
    private int accumulatedMoveX = 0;
    private int accumulatedMoveY = 0;
    private int accumulatedMoveZ = 0;

    /** Axis locks: when true, movement along that axis is suppressed. */
    private boolean lockX = false;
    private boolean lockY = false;
    private boolean lockZ = false;

    /** Error message for the last failed operation. */
    private String errorMessage = "";

    // --- Public API ---

    /**
     * Starts following mode.
     *
     * @return null on success, or an error message key suffix on failure
     *         ({@code "litematica_not_available"}, {@code "no_selection"}, {@code "already_following"})
     */
    public String start() {
        if (!LitematicaIntegration.isAvailable()) {
            return "litematica_not_available";
        }
        if (state == State.FOLLOWING) {
            return "already_following";
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return "player_not_available";
        }

        List<LitematicaIntegration.SubRegionBox> regions = LitematicaIntegration.getSubRegions();
        if (regions.isEmpty()) {
            return "no_selection";
        }

        // Use pos1 of the first sub-region as the reference point
        LitematicaIntegration.SubRegionBox first = regions.get(0);
        referenceSelectionPos = new Vec3(first.pos1().getX(), first.pos1().getY(), first.pos1().getZ());
        referencePlayerPos = client.player.position();
        accumulatedMoveX = 0;
        accumulatedMoveY = 0;
        accumulatedMoveZ = 0;
        state = State.FOLLOWING;
        errorMessage = "";
        return null;
    }

    /**
     * Stops following mode.
     *
     * @return null on success, or an error message key suffix if not following
     */
    public String stop() {
        if (state != State.FOLLOWING) {
            return "not_following";
        }
        state = State.IDLE;
        referencePlayerPos = null;
        referenceSelectionPos = null;
        accumulatedMoveX = 0;
        accumulatedMoveY = 0;
        accumulatedMoveZ = 0;
        return null;
    }

    /**
     * Locks or unlocks an axis.
     *
     * @param axis   "x", "y", or "z"
     * @param locked true to lock (suppress movement), false to unlock
     * @return null on success, or an error message key suffix
     */
    public String setAxisLock(String axis, boolean locked) {
        boolean current;
        switch (axis) {
            case "x" -> current = lockX;
            case "y" -> current = lockY;
            case "z" -> current = lockZ;
            default -> { return "invalid_axis"; }
        }

        if (current == locked) {
            return locked ? "axis_already_locked" : "axis_already_unlocked";
        }

        switch (axis) {
            case "x" -> lockX = locked;
            case "y" -> lockY = locked;
            case "z" -> lockZ = locked;
        }
        return null;
    }

    public boolean isFollowing() { return state == State.FOLLOWING; }
    public boolean isLockX() { return lockX; }
    public boolean isLockY() { return lockY; }
    public boolean isLockZ() { return lockZ; }
    public String getErrorMessage() { return errorMessage; }

    /** Returns the recorded offset from player to selection reference point. */
    public Vec3 getOffset() {
        if (referencePlayerPos == null || referenceSelectionPos == null) return null;
        return referenceSelectionPos.subtract(referencePlayerPos);
    }

    /** Returns the reference player position recorded at start. */
    public Vec3 getReferencePlayerPos() { return referencePlayerPos; }

    /** Returns the reference selection position recorded at start. */
    public Vec3 getReferenceSelectionPos() { return referenceSelectionPos; }

    // --- Tick ---

    /**
     * Called every client tick. When following, computes the desired selection
     * position from the player's current position and the recorded offset,
     * then moves the selection by the integer delta if any component has changed.
     */
    public void tick(Minecraft client) {
        if (state != State.FOLLOWING) return;
        if (client.player == null) return;
        if (!LitematicaIntegration.isAvailable()) {
            errorMessage = "Litematica not available";
            state = State.IDLE;
            return;
        }

        Vec3 playerPos = client.player.position();

        // Compute desired integer moves per axis from the reference position.
        // For locked axes, freeze the desired move at the last accumulated value
        // so delta stays zero and the selection doesn't snap back.
        int desiredMoveX = lockX
            ? accumulatedMoveX
            : (int) Math.round(playerPos.x - referencePlayerPos.x);
        int desiredMoveY = lockY
            ? accumulatedMoveY
            : (int) Math.round(playerPos.y - referencePlayerPos.y);
        int desiredMoveZ = lockZ
            ? accumulatedMoveZ
            : (int) Math.round(playerPos.z - referencePlayerPos.z);

        // Compute the incremental delta from the last applied move
        int deltaX = desiredMoveX - accumulatedMoveX;
        int deltaY = desiredMoveY - accumulatedMoveY;
        int deltaZ = desiredMoveZ - accumulatedMoveZ;

        if (deltaX == 0 && deltaY == 0 && deltaZ == 0) return;

        int count = LitematicaIntegration.moveSelection(deltaX, deltaY, deltaZ);
        if (count < 0) {
            errorMessage = "Move failed via LitematicaIntegration";
            return;
        }

        accumulatedMoveX = desiredMoveX;
        accumulatedMoveY = desiredMoveY;
        accumulatedMoveZ = desiredMoveZ;
    }
}
