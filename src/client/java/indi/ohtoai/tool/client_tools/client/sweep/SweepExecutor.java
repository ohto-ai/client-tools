package indi.ohtoai.tool.client_tools.client.sweep;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Tick-driven state machine that flies the player through a cuboid area
 * in a snake pattern. Movement only — block breaking is handled by other mods.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #start()} — build snake-path station waypoints</li>
 *   <li>{@link #tick(Minecraft)} — MOVE_TO_STATION → ADVANCE → DONE</li>
 *   <li>{@link #stop()} — halt immediately</li>
 * </ol>
 *
 * <p>Movement is <b>coordinate-based</b>: each tick sends a position packet
 * toward the target; the executor only advances when the player is within
 * 0.5 blocks. Liquid slowdown is handled naturally — no timer involved.
 */
public class SweepExecutor {

    private enum State {
        IDLE, BUILD_PATH, MOVE_TO_STATION, ADVANCE, PAUSED, ERROR, DONE
    }

    private static SweepExecutor instance;

    public static SweepExecutor getInstance() {
        if (instance == null) instance = new SweepExecutor();
        return instance;
    }

    private SweepExecutor() {}

    // --- State fields ---

    private State state = State.IDLE;
    private final List<Vec3> stationPath = new ArrayList<>();
    private int currentStationIndex = 0;
    private Vec3 targetPosition = null;
    private BlockPos cuboidMin = null;
    private BlockPos cuboidMax = null;
    private int radius = 4;
    private String errorMessage = "";
    private int totalStations = 0;
    private int waitTicks = 0;

    // --- Public API ---

    public void start() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            error("Player or level not available");
            return;
        }
        if (!SweepState.hasPositions()) {
            error("Positions not set");
            return;
        }

        // Clear any saved pause state on fresh start
        SweepState.clearPauseState();

        radius = SweepState.getRadius();

        cuboidMin = new BlockPos(SweepState.getMinX(), SweepState.getMinY(), SweepState.getMinZ());
        cuboidMax = new BlockPos(SweepState.getMaxX(), SweepState.getMaxY(), SweepState.getMaxZ());

        stationPath.clear();
        currentStationIndex = 0;
        totalStations = 0;
        waitTicks = 0;
        errorMessage = "";

        state = State.BUILD_PATH;
        SweepState.setRunning(true);
    }

    public void stop() {
        state = State.IDLE;
        stationPath.clear();
        targetPosition = null;
        SweepState.setRunning(false);
        SweepState.clearPauseState();
    }

    /** Pause at the current station — progress is persisted to disk. */
    public void pause() {
        if (state != State.MOVE_TO_STATION && state != State.ADVANCE) return;
        SweepState.savePauseState(currentStationIndex);
        state = State.PAUSED;
    }

    /** Resume from a previously paused sweep. */
    public boolean resume() {
        if (!SweepState.isPaused() || !SweepState.hasPositions()) return false;

        radius = SweepState.getRadius();
        cuboidMin = new BlockPos(SweepState.getMinX(), SweepState.getMinY(), SweepState.getMinZ());
        cuboidMax = new BlockPos(SweepState.getMaxX(), SweepState.getMaxY(), SweepState.getMaxZ());

        stationPath.clear();
        stationPath.addAll(buildSnakePath(
            cuboidMin.getX(), cuboidMax.getX(),
            cuboidMin.getY(), cuboidMax.getY(),
            cuboidMin.getZ(), cuboidMax.getZ(),
            radius));
        totalStations = stationPath.size();

        int saved = SweepState.getSavedStationIndex();
        if (saved < 0 || saved >= totalStations) {
            SweepState.clearPauseState();
            return false;
        }

        currentStationIndex = saved;
        targetPosition = stationPath.get(currentStationIndex);
        waitTicks = 0;
        errorMessage = "";
        SweepState.clearPauseState();
        SweepState.setRunning(true);
        state = State.MOVE_TO_STATION;
        return true;
    }

    public boolean isRunning() { return state != State.IDLE && state != State.DONE && state != State.ERROR && state != State.PAUSED; }
    public boolean isPaused() { return state == State.PAUSED; }
    public boolean isDone() { return state == State.DONE; }
    public boolean isError() { return state == State.ERROR; }
    public String getErrorMessage() { return errorMessage; }
    public int getCurrentStationIndex() { return currentStationIndex; }
    public int getTotalStations() { return totalStations; }
    public Vec3 getCurrentStationPos() {
        return currentStationIndex < stationPath.size() ? stationPath.get(currentStationIndex) : null;
    }
    public List<Vec3> getStationPath() { return stationPath; }

    public static boolean canStart() {
        return SweepState.hasPositions();
    }

    /**
     * Computes the snake-path station list from the current SweepState
     * without starting the executor. Useful for path preview when
     * {@code /csweep show path} is enabled.
     */
    public static List<Vec3> computePreviewPath() {
        if (!SweepState.hasPositions()) return List.of();

        int radius = SweepState.getRadius();
        int minX = SweepState.getMinX(), maxX = SweepState.getMaxX();
        int minY = SweepState.getMinY(), maxY = SweepState.getMaxY();
        int minZ = SweepState.getMinZ(), maxZ = SweepState.getMaxZ();

        return buildSnakePath(minX, maxX, minY, maxY, minZ, maxZ, radius);
    }

    /**
     * Shared snake-path algorithm: pre-computed fixed station positions,
     * X direction continuous across rows, Z sweep alternates per
     * layer but starts from where the previous layer ended (no
     * diagonal return to origin between layers).
     */
    private static List<Vec3> buildSnakePath(int minX, int maxX, int minY, int maxY,
                                              int minZ, int maxZ, int radius) {
        double spacing = Math.max(1.0, radius * 1.5);

        // Pre-compute station positions for each axis (fixed, uniform)
        int xCols = (int) Math.ceil((maxX - minX) / spacing) + 1;
        List<Integer> xStations = new ArrayList<>(xCols);
        for (int xi = 0; xi < xCols; xi++) {
            int x = minX + (int) Math.round(xi * spacing);
            xStations.add(Math.max(minX, Math.min(maxX, x)));
        }

        int zRows = (int) Math.ceil((maxZ - minZ) / spacing) + 1;
        List<Integer> zStations = new ArrayList<>(zRows);
        for (int zi = 0; zi < zRows; zi++) {
            int z = minZ + (int) Math.round(zi * spacing);
            zStations.add(Math.max(minZ, Math.min(maxZ, z)));
        }

        int yLevels = (int) Math.ceil((maxY - minY) / spacing) + 1;

        List<Vec3> path = new ArrayList<>();
        boolean goForwardX = true;      // X direction, continuous across layers
        boolean sweepZForward = true;   // Z sweep direction, alternates per layer

        for (int yi = 0; yi < yLevels; yi++) {
            int y = maxY - (int) Math.round(yi * spacing);
            y = Math.max(minY, Math.min(maxY, y));

            // Sweep through Z rows in current direction
            for (int ziActual = 0; ziActual < zRows; ziActual++) {
                int zi = sweepZForward ? ziActual : (zRows - 1 - ziActual);
                int z = zStations.get(zi);

                if (goForwardX) {
                    for (int xi = 0; xi < xCols; xi++) {
                        int x = xStations.get(xi);
                        path.add(new Vec3(x + 0.5, y + 0.5, z + 0.5));
                    }
                } else {
                    for (int xi = xCols - 1; xi >= 0; xi--) {
                        int x = xStations.get(xi);
                        path.add(new Vec3(x + 0.5, y + 0.5, z + 0.5));
                    }
                }
                goForwardX = !goForwardX;
            }

            sweepZForward = !sweepZForward;
        }

        return path;
    }

    // --- Tick ---

    public void tick(Minecraft client) {
        if (state == State.IDLE || state == State.DONE || state == State.ERROR || state == State.PAUSED) return;
        if (client.player == null || client.level == null || client.player.connection == null) return;

        switch (state) {
            case BUILD_PATH      -> doBuildPath(client);
            case MOVE_TO_STATION -> doMoveToStation(client);
            case ADVANCE         -> doAdvance(client);
            default -> {}
        }
    }

    // --- BUILD_PATH ---

    private void doBuildPath(Minecraft client) {
        stationPath.clear();
        stationPath.addAll(buildSnakePath(
            cuboidMin.getX(), cuboidMax.getX(),
            cuboidMin.getY(), cuboidMax.getY(),
            cuboidMin.getZ(), cuboidMax.getZ(),
            radius));

        totalStations = stationPath.size();
        if (stationPath.isEmpty()) {
            error("No stations in path — area may be too small");
            return;
        }

        currentStationIndex = 0;
        targetPosition = stationPath.get(0);
        state = State.MOVE_TO_STATION;
    }

    // --- MOVE_TO_STATION ---

    /**
     * Coordinate-based flight toward the current target.
     * Uses position-only packets so the player keeps full camera control.
     * Transitions to ADVANCE once the player arrives.
     */
    private void doMoveToStation(Minecraft client) {
        Vec3 current = client.player.position();
        Vec3 delta = targetPosition.subtract(current);
        double distance = delta.length();

        if (distance <= 0.5) {
            // Snap to exact target, keep player's own rotation
            client.player.setPos(targetPosition.x, targetPosition.y, targetPosition.z);
            client.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                targetPosition.x, targetPosition.y, targetPosition.z,
                client.player.onGround()
            ));
            if (waitTicks < 3) { waitTicks++; return; }
            waitTicks = 0;
            state = State.ADVANCE;
            return;
        }

        waitTicks = 0;
        double maxMove = SweepState.getSpeed() / 20.0;
        double moveAmount = Math.min(maxMove, distance);
        Vec3 direction = delta.normalize();
        Vec3 newPos = current.add(direction.scale(moveAmount));

        // Position-only packet — does not affect player rotation
        client.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            newPos.x, newPos.y, newPos.z, client.player.onGround()
        ));
        client.player.setPos(newPos.x, newPos.y, newPos.z);
    }

    // --- ADVANCE ---

    private void doAdvance(Minecraft client) {
        currentStationIndex++;
        if (currentStationIndex >= stationPath.size()) {
            finish();
            return;
        }
        targetPosition = stationPath.get(currentStationIndex);
        state = State.MOVE_TO_STATION;
    }

    // --- Completion ---

    private void finish() {
        state = State.DONE;
        SweepState.setRunning(false);
    }

    private void error(String msg) {
        errorMessage = msg;
        state = State.ERROR;
        SweepState.setRunning(false);
    }
}
