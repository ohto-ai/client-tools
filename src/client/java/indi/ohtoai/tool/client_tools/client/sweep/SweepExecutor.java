package indi.ohtoai.tool.client_tools.client.sweep;

import net.minecraft.client.Minecraft;
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
        IDLE, BUILD_PATH, APPROACHING, MOVING, PAUSED, ERROR, DONE
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

    /**
     * Pre-built path data for a single sub-region.
     */
    private record RegionData(
        String name,
        List<Vec3> stations,
        List<PathSegment> segments,
        List<Double> cumulativeDist,
        double totalLength,
        int stationCount
    ) {}

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
    private int radius = 4;
    private String errorMessage = "";
    private int totalStations = 0;

    // Multi-region support (for Litematica sub-regions)
    private final List<RegionData> regions = new ArrayList<>();
    private int currentRegionIndex = 0;
    private int globalStationOffset = 0;

    // Nearest-station highlight (set by /csweep nearest, consumed by /csweep start)
    private int nearestGlobalStationIndex = -1;
    private Vec3 nearestStationPos = null;

    // Approach state (smooth fly-in from current position to path start)
    private Vec3 approachOrigin = null;
    private Vec3 approachTarget = null;
    private double approachDistance = 0.0;
    private double approachTraveled = 0.0;
    private static final double APPROACH_THRESHOLD = 1.5; // skip approach if within this distance

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
        SweepState.refreshSubRegions();

        radius = SweepState.getRadius();

        // Resolve all sub-regions (Litematica or manual)
        List<LitematicaIntegration.SubRegionBox> subRegions = SweepState.resolveSubRegions();
        if (subRegions.isEmpty()) {
            error("No regions to sweep");
            return;
        }

        // Pre-build paths for all regions
        buildAllRegionPaths(subRegions);

        // Compute global station total
        totalStations = 0;
        for (RegionData reg : regions) totalStations += reg.stationCount();

        // If a nearest station was selected, start from there
        if (nearestGlobalStationIndex >= 0 && nearestGlobalStationIndex < totalStations) {
            int saved = nearestGlobalStationIndex;
            clearNearestStation();

            // Find which region this station falls in
            int offset = 0;
            for (int ri = 0; ri < regions.size(); ri++) {
                RegionData reg = regions.get(ri);
                int local = saved - offset;
                if (local < reg.stationCount()) {
                    currentRegionIndex = ri;
                    globalStationOffset = offset;
                    loadRegion(ri);
                    currentStationIndex = local;
                    distanceTraveled = local > 0
                        ? cumulativeDist.get(local - 1) + segments.get(local - 1).length
                        : 0.0;
                    break;
                }
                offset += reg.stationCount();
            }
        } else {
            // Start from the first region
            currentRegionIndex = 0;
            globalStationOffset = 0;
            if (!loadRegion(0)) return;
        }

        // Start smoothly: if the player is far from the first station,
        // fly from current position to the first station before sweeping
        beginMovementOrApproach(client);
    }

    /**
     * Transitions to APPROACHING if the player is far from the path start,
     * or directly to MOVING if already close enough.
     */
    private void beginMovementOrApproach(Minecraft client) {
        if (stationPath.isEmpty()) {
            error("No path stations");
            return;
        }

        Vec3 playerPos = client.player.position();
        Vec3 targetPos = stationPath.get(0);
        double dist = playerPos.distanceTo(targetPos);

        if (dist > APPROACH_THRESHOLD) {
            // Fly smoothly from current position to the first station
            approachOrigin = playerPos;
            approachTarget = targetPos;
            approachDistance = dist;
            approachTraveled = 0.0;
            state = State.APPROACHING;
            SweepState.setRunning(true);
        } else {
            // Already close enough — start sweeping immediately
            state = State.MOVING;
            SweepState.setRunning(true);
        }
    }

    public void stop() {
        state = State.IDLE;
        resetPath();
        resetApproach();
        SweepState.setRunning(false);
        SweepState.clearPauseState();
    }

    /** Jumps to the next sub-region if one exists. */
    public boolean skipToNextRegion() {
        if (state != State.MOVING && state != State.PAUSED) return false;
        if (currentRegionIndex >= regions.size() - 1) return false;
        globalStationOffset += regions.get(currentRegionIndex).stationCount();
        currentRegionIndex++;
        if (!loadRegion(currentRegionIndex)) return false;
        if (state == State.PAUSED) state = State.MOVING;
        return true;
    }

    /** Pause at the current position — progress is persisted to disk. */
    public void pause() {
        if (state != State.MOVING) return;
        SweepState.savePauseStationIndex(globalStationOffset + currentStationIndex);
        state = State.PAUSED;
    }

    /**
     * Called on disconnect to auto-save sweep progress.
     * If the sweep is running, saves the current position and marks
     * the sweep as unfinished so the player is reminded on rejoin.
     */
    public void handleDisconnect() {
        if (state == State.MOVING || state == State.APPROACHING) {
            int index;
            if (state == State.APPROACHING) {
                // Haven't reached a station yet — save as station 0
                index = globalStationOffset;
            } else {
                index = globalStationOffset + currentStationIndex;
            }
            SweepState.markUnfinished(index);
            state = State.IDLE;
            resetPath();
        }
    }

    /** Resume from a previously paused sweep (multi-region aware). */
    public boolean resume() {
        if (!SweepState.isPaused() || !SweepState.hasPositions()) return false;

        radius = SweepState.getRadius();
        SweepState.refreshSubRegions();

        List<LitematicaIntegration.SubRegionBox> subRegions = SweepState.resolveSubRegions();
        if (subRegions.isEmpty()) return false;

        buildAllRegionPaths(subRegions);
        totalStations = 0;
        for (RegionData reg : regions) totalStations += reg.stationCount();

        int savedGlobalStation = SweepState.getSavedStationIndex();
        if (savedGlobalStation < 0 || savedGlobalStation >= totalStations) {
            SweepState.clearPauseState();
            return false;
        }

        // Find which region the saved global station falls in
        int offset = 0;
        boolean found = false;
        for (int ri = 0; ri < regions.size(); ri++) {
            RegionData reg = regions.get(ri);
            int localStation = savedGlobalStation - offset;
            if (localStation < reg.stationCount()) {
                currentRegionIndex = ri;
                globalStationOffset = offset;
                if (!loadRegion(ri)) return false;
                currentStationIndex = localStation;
                distanceTraveled = localStation > 0
                    ? cumulativeDist.get(localStation - 1) + segments.get(localStation - 1).length
                    : 0.0;
                found = true;
                break;
            }
            offset += reg.stationCount();
        }
        if (!found) {
            SweepState.clearPauseState();
            return false;
        }

        errorMessage = "";
        SweepState.clearPauseState();

        // Compute the target position on the path at the resume point
        Vec3 targetPos;
        if (stationPath.isEmpty()) {
            SweepState.clearPauseState();
            return false;
        }
        if (distanceTraveled <= 0.0) {
            targetPos = stationPath.get(0);
        } else if (distanceTraveled >= totalPathLength) {
            targetPos = stationPath.get(stationPath.size() - 1);
        } else {
            int segIdx = findSegment(distanceTraveled);
            PathSegment seg = segments.get(segIdx);
            double segStartDist = cumulativeDist.get(segIdx);
            double t = seg.length > 0 ? (distanceTraveled - segStartDist) / seg.length : 0.0;
            t = Math.max(0.0, Math.min(1.0, t));
            targetPos = new Vec3(
                seg.start.x + (seg.end.x - seg.start.x) * t,
                seg.start.y + (seg.end.y - seg.start.y) * t,
                seg.start.z + (seg.end.z - seg.start.z) * t
            );
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;

        Vec3 playerPos = client.player.position();
        double dist = playerPos.distanceTo(targetPos);

        if (dist > APPROACH_THRESHOLD) {
            approachOrigin = playerPos;
            approachTarget = targetPos;
            approachDistance = dist;
            approachTraveled = 0.0;
            state = State.APPROACHING;
        } else {
            state = State.MOVING;
        }

        SweepState.setRunning(true);
        return true;
    }

    public boolean isRunning() { return state == State.MOVING || state == State.APPROACHING; }
    public boolean isPaused() { return state == State.PAUSED; }
    public boolean isDone() { return state == State.DONE; }
    public boolean isError() { return state == State.ERROR; }
    public String getErrorMessage() { return errorMessage; }
    /** Global station index across all regions. */
    public int getCurrentStationIndex() { return globalStationOffset + currentStationIndex; }
    public int getTotalStations() { return totalStations; }
    public int getCurrentRegionIndex() { return currentRegionIndex; }
    public int getRegionCount() { return regions.size(); }
    public String getCurrentRegionName() {
        return currentRegionIndex < regions.size() ? regions.get(currentRegionIndex).name() : "";
    }
    public Vec3 getCurrentStationPos() {
        return currentStationIndex < stationPath.size() ? stationPath.get(currentStationIndex) : null;
    }
    /** Returns the path for the currently active region only. */
    public List<Vec3> getStationPath() { return stationPath; }

    /** Returns the concatenated path across all regions (for renderer preview). */
    public List<Vec3> getFullConcatPath() {
        if (regions.isEmpty()) return stationPath;
        List<Vec3> full = new ArrayList<>();
        for (RegionData reg : regions) full.addAll(reg.stations());
        return full;
    }

    public static boolean canStart() {
        return SweepState.hasPositions();
    }

    // --- Nearest station (for /csweep nearest) ---

    public int getNearestStationIndex() { return nearestGlobalStationIndex; }
    public Vec3 getNearestStationPos() { return nearestStationPos; }
    public boolean hasNearestStation() { return nearestGlobalStationIndex >= 0 && nearestStationPos != null; }

    /**
     * Finds the station nearest to the player in the full sweep path,
     * stores its index and position, so the renderer can highlight it
     * and the next {@link #start()} resumes from that station.
     *
     * @return info string: "Station X / Y (Z blocks away)"
     */
    public String findNearestStation() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return null;
        Vec3 playerPos = client.player.position();

        // Rebuild all region paths
        SweepState.refreshSubRegions();
        List<LitematicaIntegration.SubRegionBox> subRegions = SweepState.resolveSubRegions();
        if (subRegions.isEmpty()) return null;

        radius = SweepState.getRadius();
        regions.clear();
        buildAllRegionPaths(subRegions);
        totalStations = 0;
        for (RegionData reg : regions) totalStations += reg.stationCount();

        // Find the nearest station across all regions
        double bestDist = Double.MAX_VALUE;
        int bestGlobalIdx = -1;
        Vec3 bestPos = null;
        String bestRegionName = "";
        int globalIdx = 0;
        for (RegionData reg : regions) {
            for (Vec3 station : reg.stations()) {
                double dist = playerPos.distanceToSqr(station);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestGlobalIdx = globalIdx;
                    bestPos = station;
                    bestRegionName = reg.name();
                }
                globalIdx++;
            }
        }

        if (bestGlobalIdx < 0) return null;

        nearestGlobalStationIndex = bestGlobalIdx;
        nearestStationPos = bestPos;

        double distance = Math.sqrt(bestDist);
        String regionInfo = regions.size() > 1 ? " [" + bestRegionName + "]" : "";
        return "Station " + (bestGlobalIdx + 1) + " / " + totalStations
            + " (" + String.format("%.1f", distance) + " blocks away)" + regionInfo;
    }

    public void clearNearestStation() {
        nearestGlobalStationIndex = -1;
        nearestStationPos = null;
    }

    // --- Path preview ---

    public static List<Vec3> computePreviewPath() {
        if (!SweepState.hasPositions()) return List.of();
        int r = SweepState.getRadius();
        List<LitematicaIntegration.SubRegionBox> subRegions = SweepState.resolveSubRegions();
        if (subRegions.isEmpty()) return List.of();
        List<Vec3> allPaths = new ArrayList<>();
        for (LitematicaIntegration.SubRegionBox box : subRegions) {
            allPaths.addAll(buildSnakePath(
                Math.min(box.pos1().getX(), box.pos2().getX()),
                Math.max(box.pos1().getX(), box.pos2().getX()),
                Math.min(box.pos1().getY(), box.pos2().getY()),
                Math.max(box.pos1().getY(), box.pos2().getY()),
                Math.min(box.pos1().getZ(), box.pos2().getZ()),
                Math.max(box.pos1().getZ(), box.pos2().getZ()), r));
        }
        return allPaths;
    }

    // --- Tick ---

    public void tick(Minecraft client) {
        if (state != State.MOVING && state != State.APPROACHING) return;
        if (client.player == null || client.level == null || client.player.connection == null) return;

        if (state == State.APPROACHING) {
            doApproach(client);
        } else {
            doMove(client);
        }
    }

    // --- APPROACHING (smooth fly-in to path start) ---

    private void doApproach(Minecraft client) {
        double step = SweepState.getSpeed() / 20.0;
        approachTraveled += step;

        if (approachTraveled >= approachDistance) {
            // Arrived — snap to target and transition to MOVING
            client.player.setPos(approachTarget.x, approachTarget.y, approachTarget.z);
            client.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                approachTarget.x, approachTarget.y, approachTarget.z, client.player.onGround()));
            state = State.MOVING;
            return;
        }

        // Smooth interpolation from origin to target
        double t = approachDistance > 0 ? approachTraveled / approachDistance : 1.0;
        t = Math.max(0.0, Math.min(1.0, t));

        Vec3 pos = new Vec3(
            approachOrigin.x + (approachTarget.x - approachOrigin.x) * t,
            approachOrigin.y + (approachTarget.y - approachOrigin.y) * t,
            approachOrigin.z + (approachTarget.z - approachOrigin.z) * t
        );

        client.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            pos.x, pos.y, pos.z, client.player.onGround()));
        client.player.setPos(pos.x, pos.y, pos.z);
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

    // --- Multi-region path building ---

    /**
     * Pre-builds path data for all sub-regions before sweeping begins.
     */
    private void buildAllRegionPaths(List<LitematicaIntegration.SubRegionBox> subRegions) {
        regions.clear();
        for (LitematicaIntegration.SubRegionBox box : subRegions) {
            int minX = Math.min(box.pos1().getX(), box.pos2().getX());
            int maxX = Math.max(box.pos1().getX(), box.pos2().getX());
            int minY = Math.min(box.pos1().getY(), box.pos2().getY());
            int maxY = Math.max(box.pos1().getY(), box.pos2().getY());
            int minZ = Math.min(box.pos1().getZ(), box.pos2().getZ());
            int maxZ = Math.max(box.pos1().getZ(), box.pos2().getZ());

            List<Vec3> stations = buildSnakePath(minX, maxX, minY, maxY, minZ, maxZ, radius);
            List<PathSegment> segs = new ArrayList<>();
            List<Double> cumDist = new ArrayList<>();
            cumDist.add(0.0);
            double cum = 0.0;
            for (int i = 0; i < stations.size() - 1; i++) {
                PathSegment seg = new PathSegment(stations.get(i), stations.get(i + 1));
                segs.add(seg);
                cum += seg.length;
                cumDist.add(cum);
            }
            double totalLen = cum;
            regions.add(new RegionData(box.name(), stations, segs, cumDist, totalLen, stations.size()));
        }
    }

    /**
     * Swaps the active path data to the given region's pre-built data.
     */
    private boolean loadRegion(int index) {
        if (index < 0 || index >= regions.size()) return false;
        RegionData reg = regions.get(index);
        stationPath.clear();
        stationPath.addAll(reg.stations());
        segments.clear();
        segments.addAll(reg.segments());
        cumulativeDist.clear();
        cumulativeDist.addAll(reg.cumulativeDist());
        totalPathLength = reg.totalLength();
        distanceTraveled = 0.0;
        currentStationIndex = 1; // heading to first station of this region
        errorMessage = "";
        return true;
    }

    // --- Completion ---

    private void finish() {
        if (currentRegionIndex < regions.size() - 1) {
            // Save the last position of the current region for a smooth transition
            Vec3 lastPos = stationPath.isEmpty() ? null : stationPath.get(stationPath.size() - 1);

            globalStationOffset += regions.get(currentRegionIndex).stationCount();
            currentRegionIndex++;
            if (!loadRegion(currentRegionIndex)) {
                error("Failed to load next region");
                return;
            }

            // Prepend a transition segment from the previous region's end
            // to the next region's first station, so the player flies smoothly
            // instead of teleporting.
            if (lastPos != null && !stationPath.isEmpty()) {
                Vec3 firstStation = stationPath.get(0);
                double gap = lastPos.distanceTo(firstStation);
                if (gap > 0.5) {
                    stationPath.add(0, lastPos);
                    // Rebuild segments and cumulative distances
                    segments.clear();
                    cumulativeDist.clear();
                    cumulativeDist.add(0.0);
                    double cum = 0.0;
                    for (int i = 0; i < stationPath.size() - 1; i++) {
                        PathSegment seg = new PathSegment(stationPath.get(i), stationPath.get(i + 1));
                        segments.add(seg);
                        cum += seg.length;
                        cumulativeDist.add(cum);
                    }
                    totalPathLength = cum;
                    distanceTraveled = 0.0;
                    currentStationIndex = 1;
                }
            }
            // Continue in MOVING state — doMove handles the next region seamlessly
        } else {
            state = State.DONE;
            currentStationIndex = totalStations;
            SweepState.setRunning(false);
        }
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
        regions.clear();
        currentRegionIndex = 0;
        globalStationOffset = 0;
    }

    private void resetApproach() {
        approachOrigin = null;
        approachTarget = null;
        approachDistance = 0.0;
        approachTraveled = 0.0;
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
