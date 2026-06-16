package indi.ohtoai.tool.client_tools.client.sweep;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Tick-driven state machine that flies the player through a cuboid area
 * in a snake pattern at constant speed. Movement only — block breaking
 * is handled by other mods.
 *
 * <p>Movement is <b>continuous and smooth</b>: the player advances along
 * the path at a constant rate each tick, with linear interpolation between
 * stations. No stop-and-go at each waypoint.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #start()} — build snake-path, pre-compute segment distances</li>
 *   <li>{@link #tick(Minecraft)} — MOVING (advance along path each tick) → DONE</li>
 *   <li>{@link #stop()} — halt immediately</li>
 * </ol>
 */
public class SweepExecutor {

    private enum State {
        IDLE, BUILD_PATH, MOVING, PAUSED, ERROR, DONE
    }

    // --- Inner data class for path segments ---

    private static class PathSegment {
        final Vec3 start;       // segment start position
        final Vec3 end;         // segment end position
        final double length;    // Euclidean distance

        PathSegment(Vec3 start, Vec3 end) {
            this.start = start;
            this.end = end;
            this.length = start.distanceTo(end);
        }
    }

    // --- Singleton ---

    private static SweepExecutor instance;

    public static SweepExecutor getInstance() {
        if (instance == null) instance = new SweepExecutor();
        return instance;
    }

    private SweepExecutor() {}

    // --- State fields ---

    private State state = State.IDLE;
    private final List<Vec3> stationPath = new ArrayList<>();
    private final List<PathSegment> segments = new ArrayList<>();
    private final List<Double> cumulativeDist = new ArrayList<>(); // distance from start to each station
    private double totalPathLength = 0.0;
    private double distanceTraveled = 0.0;  // how far along the path we are
    private int currentStationIndex = 0;     // derived: next station we're heading toward
    private BlockPos cuboidMin = null;
    private BlockPos cuboidMax = null;
    private int radius = 4;
    private String errorMessage = "";
    private int totalStations = 0;

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

        SweepState.clearPauseState();

        radius = SweepState.getRadius();
        cuboidMin = new BlockPos(SweepState.getMinX(), SweepState.getMinY(), SweepState.getMinZ());
        cuboidMax = new BlockPos(SweepState.getMaxX(), SweepState.getMaxY(), SweepState.getMaxZ());

        resetPath();

        state = State.BUILD_PATH;
        SweepState.setRunning(true);
    }

    public void stop() {
        state = State.IDLE;
        resetPath();
        SweepState.setRunning(false);
        SweepState.clearPauseState();
    }

    /** Pause at the current position — progress is persisted to disk. */
    public void pause() {
        if (state != State.MOVING) return;
        SweepState.savePauseStationIndex(currentStationIndex);
        state = State.PAUSED;
    }

    /** Resume from a previously paused sweep. */
    public boolean resume() {
        if (!SweepState.isPaused() || !SweepState.hasPositions()) return false;

        radius = SweepState.getRadius();
        cuboidMin = new BlockPos(SweepState.getMinX(), SweepState.getMinY(), SweepState.getMinZ());
        cuboidMax = new BlockPos(SweepState.getMaxX(), SweepState.getMaxY(), SweepState.getMaxZ());

        if (!buildPathAndSegments()) return false;

        int saved = SweepState.getSavedStationIndex();
        if (saved < 0 || saved >= totalStations) {
            SweepState.clearPauseState();
            return false;
        }

        currentStationIndex = saved;
        distanceTraveled = saved > 0 ? cumulativeDist.get(saved - 1) + segments.get(saved - 1).length : 0.0;
        errorMessage = "";
        SweepState.clearPauseState();
        SweepState.setRunning(true);
        state = State.MOVING;
        return true;
    }

    public boolean isRunning() { return state == State.MOVING; }
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

    // --- Path preview ---

    public static List<Vec3> computePreviewPath() {
        if (!SweepState.hasPositions()) return List.of();
        int r = SweepState.getRadius();
        return buildSnakePath(
            SweepState.getMinX(), SweepState.getMaxX(),
            SweepState.getMinY(), SweepState.getMaxY(),
            SweepState.getMinZ(), SweepState.getMaxZ(), r);
    }

    // --- Tick ---

    public void tick(Minecraft client) {
        if (state != State.BUILD_PATH && state != State.MOVING) return;
        if (client.player == null || client.level == null || client.player.connection == null) return;

        switch (state) {
            case BUILD_PATH -> doBuildPath(client);
            case MOVING     -> doMove(client);
            default -> {}
        }
    }

    // --- BUILD_PATH ---

    private void doBuildPath(Minecraft client) {
        if (!buildPathAndSegments()) return;
        distanceTraveled = 0.0;
        currentStationIndex = 1; // heading to first station
        state = State.MOVING;
    }

    private boolean buildPathAndSegments() {
        stationPath.clear();
        stationPath.addAll(buildSnakePath(
            cuboidMin.getX(), cuboidMax.getX(),
            cuboidMin.getY(), cuboidMax.getY(),
            cuboidMin.getZ(), cuboidMax.getZ(), radius));
        totalStations = stationPath.size();
        if (stationPath.isEmpty()) {
            error("No stations in path");
            return false;
        }

        // Build segments and cumulative distances
        segments.clear();
        cumulativeDist.clear();
        cumulativeDist.add(0.0); // distance at station 0
        double cum = 0.0;
        for (int i = 0; i < stationPath.size() - 1; i++) {
            PathSegment seg = new PathSegment(stationPath.get(i), stationPath.get(i + 1));
            segments.add(seg);
            cum += seg.length;
            cumulativeDist.add(cum);
        }
        totalPathLength = cum;
        return true;
    }

    // --- MOVING (continuous interpolation) ---

    private void doMove(Minecraft client) {
        if (segments.isEmpty()) {
            finish();
            return;
        }

        // Advance along the path at constant speed
        double step = SweepState.getSpeed() / 20.0;
        distanceTraveled += step;

        if (distanceTraveled >= totalPathLength) {
            // Reached end — snap to final station
            Vec3 end = stationPath.get(stationPath.size() - 1);
            client.player.setPos(end.x, end.y, end.z);
            client.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                end.x, end.y, end.z, client.player.onGround()));
            finish();
            return;
        }

        // Find which segment we're in
        int segIdx = findSegment(distanceTraveled);
        currentStationIndex = segIdx + 1; // next station we're heading toward

        // Interpolate position along this segment
        PathSegment seg = segments.get(segIdx);
        double segStartDist = cumulativeDist.get(segIdx);
        double t = seg.length > 0 ? (distanceTraveled - segStartDist) / seg.length : 0.0;
        t = Math.max(0.0, Math.min(1.0, t));

        Vec3 pos = seg.start.add(
            (seg.end.x - seg.start.x) * t,
            (seg.end.y - seg.start.y) * t,
            (seg.end.z - seg.start.z) * t
        );

        client.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            pos.x, pos.y, pos.z, client.player.onGround()));
        client.player.setPos(pos.x, pos.y, pos.z);
    }

    /** Binary search for the segment containing the given distance. */
    private int findSegment(double dist) {
        int lo = 0, hi = segments.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cumulativeDist.get(mid) <= dist) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    // --- Completion ---

    private void finish() {
        state = State.DONE;
        currentStationIndex = totalStations;
        SweepState.setRunning(false);
    }

    private void error(String msg) {
        errorMessage = msg;
        state = State.ERROR;
        SweepState.setRunning(false);
    }

    private void resetPath() {
        stationPath.clear();
        segments.clear();
        cumulativeDist.clear();
        totalPathLength = 0.0;
        distanceTraveled = 0.0;
        currentStationIndex = 0;
        totalStations = 0;
    }

    // --- Shared snake-path algorithm ---

    private static List<Vec3> buildSnakePath(int minX, int maxX, int minY, int maxY,
                                              int minZ, int maxZ, int radius) {
        double spacing = Math.max(1.0, radius * 1.5);

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
        boolean goForwardX = true;
        boolean sweepZForward = true;

        for (int yi = 0; yi < yLevels; yi++) {
            int y = maxY - (int) Math.round(yi * spacing);
            y = Math.max(minY, Math.min(maxY, y));

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
}
