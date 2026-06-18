package indi.ohtoai.tool.client_tools.client.sweep;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

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
    private double completedRegionsLength = 0.0;   // sum of totalLength for regions already finished
    private double totalAllRegionsLength = 0.0;    // sum of totalLength for ALL regions

    // Nearest-station highlight (set by /csweep nearest, consumed by /csweep start)
    private int nearestGlobalStationIndex = -1;
    private Vec3 nearestStationPos = null;
    private Vec3 nearestStationPrevPos = null;
    private Vec3 nearestStationNextPos = null;

    // Real-time nearest station tracking
    private boolean nearestTrackingEnabled = false;
    private int nearestTrackTickCounter = 0;
    private static final int NEAREST_TRACK_INTERVAL = 10;
    private int cachedAreaVersion = -1;
    private int cachedRadius = -1;
    private List<LitematicaIntegration.SubRegionBox> cachedSubRegions = null;

    // Approach state (smooth fly-in from current position to path start)
    private Vec3 approachOrigin = null;
    private Vec3 approachTarget = null;
    private double approachDistance = 0.0;
    private double approachTraveled = 0.0;
    private static final double APPROACH_THRESHOLD = 1.5; // skip approach if within this distance

    // Block density scan (for adaptive speed)
    private int densityScanTickCounter = 0;
    private double cachedDensity = 0.0;
    private static final int DENSITY_SCAN_INTERVAL = 10; // scan every 10 ticks (2 Hz)

    // Movement-block detection (server rejected the position packet)
    private Vec3 lastActualPosition = null;
    private int blockedTicks = 0;
    private static final int BLOCKED_TICK_THRESHOLD = 3;   // consecutive blocked ticks to trigger
    private static final double MOVE_PROGRESS_RATIO = 0.3;  // actual/expected ratio below which = blocked

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

        recomputeCompletedRegionsLength();

        // Start smoothly: if the player is far from the first station,
        // fly from current position to the first station before sweeping
        beginMovementOrApproach(client);
    }

    /**
     * Transitions to APPROACHING if the player is far from the current
     * position on the path, or directly to MOVING if already close enough.
     */
    private void beginMovementOrApproach(Minecraft client) {
        if (stationPath.isEmpty()) {
            error("No path stations");
            return;
        }

        Vec3 playerPos = client.player.position();

        // Compute the actual target position on the path at distanceTraveled
        Vec3 targetPos;
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

        double dist = playerPos.distanceTo(targetPos);

        if (dist > APPROACH_THRESHOLD) {
            // Fly smoothly from current position to the target station
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
        clearActionBar();
    }

    /** Jumps to the next sub-region if one exists. */
    public boolean skipToNextRegion() {
        if (state != State.MOVING && state != State.PAUSED) return false;
        if (currentRegionIndex >= regions.size() - 1) return false;
        completedRegionsLength += regions.get(currentRegionIndex).totalLength();
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
        showActionBarPaused();
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
            clearActionBar();
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

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;

        recomputeCompletedRegionsLength();
        beginMovementOrApproach(client);
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

    // --- Progress tracking (distance-based, for status progress bar) ---

    /** How far the player has traveled along the active region's path. */
    public double getDistanceTraveled() { return distanceTraveled; }
    /** Total length of the active region's snake path. */
    public double getTotalPathLength() { return totalPathLength; }
    /** Sum of totalLength for all regions already completed. */
    public double getCompletedRegionsLength() { return completedRegionsLength; }
    /** Sum of totalLength for ALL regions. */
    public double getTotalAllRegionsLength() { return totalAllRegionsLength; }
    /** Whether the executor is currently in the approaching phase. */
    public boolean isApproaching() { return state == State.APPROACHING; }

    /** Recomputes {@link #completedRegionsLength} from the current region index. */
    private void recomputeCompletedRegionsLength() {
        completedRegionsLength = 0.0;
        for (int i = 0; i < currentRegionIndex && i < regions.size(); i++) {
            completedRegionsLength += regions.get(i).totalLength();
        }
    }

    // --- Action bar (real-time progress during sweep) ---

    private static final int ACTION_BAR_WIDTH = 10;

    /** Updates the action bar with a compact progress bar, percentage, and ETA. */
    private void updateActionBar(Minecraft client) {
        if (totalAllRegionsLength <= 0 || client.player == null) return;

        double completedDist = completedRegionsLength + distanceTraveled;
        double fraction = Math.max(0.0, Math.min(1.0, completedDist / totalAllRegionsLength));
        double pct = fraction * 100.0;

        int filled = (int) Math.round(fraction * ACTION_BAR_WIDTH);
        StringBuilder bar = new StringBuilder("§a[");
        for (int i = 0; i < filled; i++) bar.append('█');
        bar.append("§7");
        for (int i = filled; i < ACTION_BAR_WIDTH; i++) bar.append('░');
        bar.append("§a]");

        double speed = SweepState.isAutoSpeed()
            ? (SweepState.getMaxSpeed() - cachedDensity * (SweepState.getMaxSpeed() - SweepState.getSpeed()))
            : SweepState.getSpeed();
        double remainingDist = totalAllRegionsLength - completedDist;
        long etaSeconds = speed > 0 ? (long) Math.ceil(remainingDist / speed) : 0;

        int station = globalStationOffset + currentStationIndex;
        String speedPart = SweepState.isAutoSpeed()
            ? ("§dAUTO §f" + String.format("%.1f", speed))
            : ("§f" + String.format("%.1f", speed));
        String msg = bar + " §b" + String.format("%.1f", pct) + "%% §7| §e"
            + compactEta(etaSeconds) + " §7| " + speedPart + " §7| §f" + station + "/" + totalStations;
        client.player.displayClientMessage(Component.literal(msg), true);
    }

    /** Clears the action bar (sends an empty string). */
    private void clearActionBar() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal(" "), true);
        }
    }

    /** Shows a paused indicator on the action bar. */
    private void showActionBarPaused() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        int station = globalStationOffset + currentStationIndex;
        String msg = "§e⏸ PAUSED §7| §f" + station + "/" + totalStations
            + " §7— §e/csweep pause §7to resume";
        client.player.displayClientMessage(Component.literal(msg), true);
    }

    /** Compact ETA: "1h23m", "2m35s", "45s". */
    private static String compactEta(long s) {
        if (s >= 3600) {
            long h = s / 3600;
            long m = (s % 3600) / 60;
            return h + "h" + m + "m";
        } else if (s >= 60) {
            long m = s / 60;
            long sec = s % 60;
            return m + "m" + sec + "s";
        } else {
            return s + "s";
        }
    }

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
    public Vec3 getNearestStationPrevPos() { return nearestStationPrevPos; }
    public Vec3 getNearestStationNextPos() { return nearestStationNextPos; }
    public boolean hasNearestStation() { return nearestGlobalStationIndex >= 0 && nearestStationPos != null; }
    public boolean isNearestTrackingEnabled() { return nearestTrackingEnabled; }
    public void setNearestTrackingEnabled(boolean v) { nearestTrackingEnabled = v; }

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
        computeNearestNeighbors(bestGlobalIdx);

        // Cache the built paths for real-time tracking reuse
        cachedAreaVersion = SweepState.getAreaVersion();
        cachedRadius = radius;
        cachedSubRegions = new ArrayList<>(subRegions);

        double distance = Math.sqrt(bestDist);
        String regionInfo = regions.size() > 1 ? " [" + bestRegionName + "]" : "";
        return "Station " + (bestGlobalIdx + 1) + " / " + totalStations
            + " (" + String.format("%.1f", distance) + " blocks away)" + regionInfo;
    }

    public void clearNearestStation() {
        nearestGlobalStationIndex = -1;
        nearestStationPos = null;
        nearestStationPrevPos = null;
        nearestStationNextPos = null;
    }

    /**
     * Computes the previous and next station positions in the full concatenated
     * path for the given global station index, for path-direction display.
     */
    private void computeNearestNeighbors(int globalIdx) {
        nearestStationPrevPos = null;
        nearestStationNextPos = null;

        if (regions.isEmpty()) return;

        int offset = 0;
        for (int ri = 0; ri < regions.size(); ri++) {
            RegionData reg = regions.get(ri);
            int localIdx = globalIdx - offset;
            if (localIdx >= 0 && localIdx < reg.stationCount()) {
                List<Vec3> stations = reg.stations();
                if (localIdx > 0) {
                    nearestStationPrevPos = stations.get(localIdx - 1);
                } else if (ri > 0) {
                    List<Vec3> prevStations = regions.get(ri - 1).stations();
                    nearestStationPrevPos = prevStations.get(prevStations.size() - 1);
                }
                if (localIdx < stations.size() - 1) {
                    nearestStationNextPos = stations.get(localIdx + 1);
                } else if (ri < regions.size() - 1) {
                    List<Vec3> nextStations = regions.get(ri + 1).stations();
                    nearestStationNextPos = nextStations.get(0);
                }
                return;
            }
            offset += reg.stationCount();
        }
    }

    // --- Real-time nearest station tracking ---

    /**
     * Called every {@link #NEAREST_TRACK_INTERVAL} ticks when idle and
     * tracking is enabled. Rebuilds the path cache if the sweep configuration
     * has changed, then finds the nearest station from the cache.
     */
    private void updateNearestTracking(Minecraft client) {
        if (client.player == null) {
            clearNearestStation();
            return;
        }
        Vec3 playerPos = client.player.position();

        SweepState.refreshSubRegions();
        List<LitematicaIntegration.SubRegionBox> subRegions = SweepState.resolveSubRegions();
        if (subRegions.isEmpty()) {
            clearNearestStation();
            return;
        }

        int currentVersion = SweepState.getAreaVersion();
        int currentRadius = SweepState.getRadius();
        boolean configChanged = (cachedAreaVersion != currentVersion)
            || (cachedRadius != currentRadius)
            || (cachedSubRegions == null)
            || (!subRegions.equals(cachedSubRegions));

        if (configChanged) {
            radius = currentRadius;
            regions.clear();
            buildAllRegionPaths(subRegions);
            totalStations = 0;
            for (RegionData reg : regions) totalStations += reg.stationCount();
            cachedAreaVersion = currentVersion;
            cachedRadius = currentRadius;
            cachedSubRegions = new ArrayList<>(subRegions);
        }

        findNearestFromCache(playerPos);
    }

    /**
     * Finds the station nearest to the given position by iterating over
     * the cached {@link #regions} list. Does NOT rebuild any paths.
     */
    private void findNearestFromCache(Vec3 playerPos) {
        double bestDistSq = Double.MAX_VALUE;
        int bestGlobalIdx = -1;
        Vec3 bestPos = null;

        int globalIdx = 0;
        for (RegionData reg : regions) {
            for (Vec3 station : reg.stations()) {
                double distSq = playerPos.distanceToSqr(station);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestGlobalIdx = globalIdx;
                    bestPos = station;
                }
                globalIdx++;
            }
        }

        if (bestGlobalIdx < 0) {
            clearNearestStation();
            return;
        }

        nearestGlobalStationIndex = bestGlobalIdx;
        nearestStationPos = bestPos;
        computeNearestNeighbors(bestGlobalIdx);
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
        // Real-time nearest station tracking (when idle and tracking enabled)
        if (state == State.IDLE && nearestTrackingEnabled) {
            nearestTrackTickCounter++;
            if (nearestTrackTickCounter >= NEAREST_TRACK_INTERVAL) {
                nearestTrackTickCounter = 0;
                updateNearestTracking(client);
            }
            return;
        }

        if (state != State.MOVING && state != State.APPROACHING) return;
        if (client.player == null || client.level == null || client.player.connection == null) return;

        if (state == State.APPROACHING) {
            doApproach(client);
        } else {
            doMove(client);
        }

        // Real-time action bar progress
        if (client.player != null && (state == State.MOVING || state == State.APPROACHING)) {
            updateActionBar(client);
        }
    }

    // --- APPROACHING (smooth fly-in to path start) ---

    private void doApproach(Minecraft client) {
        // Compute direction for density scan
        Vec3 direction = null;
        if (approachTarget != null && approachOrigin != null) {
            Vec3 dir = approachTarget.subtract(approachOrigin);
            if (dir.lengthSqr() > 1e-6) {
                direction = dir.normalize();
            }
        }

        double effectiveSpeed = getEffectiveSpeed(
            approachOrigin != null ? approachOrigin : client.player.position(), direction);
        double step = effectiveSpeed / 20.0;
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

        // Determine movement direction from current segment
        int segIdx = findSegment(distanceTraveled);
        Vec3 direction = null;
        if (segIdx < segments.size()) {
            PathSegment seg = segments.get(segIdx);
            Vec3 dir = seg.end.subtract(seg.start);
            if (dir.lengthSqr() > 1e-6) {
                direction = dir.normalize();
            }
        }

        // Compute effective speed (adaptive or constant)
        double effectiveSpeed = getEffectiveSpeed(client.player.position(), direction);

        // Advance along the path
        double step = effectiveSpeed / 20.0;
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

        // Determine which segment we're now in (may have changed after the step)
        segIdx = findSegment(distanceTraveled);
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

    // --- Adaptive speed (block-density-based) ---

    /**
     * Computes the effective movement speed for the current tick.
     * When auto speed is enabled, uses a three-tier regulation system:
     * <ol>
     *   <li><b>Emergency brake</b> — player body inside a solid block → min speed</li>
     *   <li><b>Movement blockage</b> — server rejecting position packets → min speed</li>
     *   <li><b>Water penalty</b> — head underwater → min speed (avoids mining speed penalty)</li>
     *   <li><b>Density scan</b> — block density ahead → smooth interpolation</li>
     * </ol>
     */
    private double getEffectiveSpeed(Vec3 playerPos, Vec3 direction) {
        if (!SweepState.isAutoSpeed()) {
            return SweepState.getSpeed();
        }
        double minSpeed = SweepState.getSpeed();
        double maxSpeed = SweepState.getMaxSpeed();
        if (maxSpeed < minSpeed) {
            return minSpeed;
        }

        Minecraft client = Minecraft.getInstance();

        // Tier 1: player body embedded in a solid block
        if (checkPlayerStuck(client)) {
            blockedTicks = BLOCKED_TICK_THRESHOLD; // also mark as blocked
            return minSpeed;
        }

        // Tier 2: server rejected our movement — player isn't actually advancing
        if (checkMovementBlocked(client)) {
            return minSpeed;
        }

        // Tier 3: head in water → 5× mining penalty (25× if also floating)
        // Slow to min speed so the miner/printer has enough time per block
        if (checkPlayerInWater(client)) {
            return minSpeed;
        }

        // Tier 4: predictive density scan ahead
        densityScanTickCounter++;
        if (densityScanTickCounter >= DENSITY_SCAN_INTERVAL && direction != null) {
            densityScanTickCounter = 0;
            cachedDensity = computeBlockDensity(playerPos, direction);
        }

        return maxSpeed - cachedDensity * (maxSpeed - minSpeed);
    }

    /**
     * Detects whether the player's head is submerged in water.
     * Mining while underwater incurs a severe speed penalty (5×–25×),
     * so the sweep should slow down to give the mining tool enough time.
     */
    private boolean checkPlayerInWater(Minecraft client) {
        if (client.player == null) return false;
        return client.player.isEyeInFluid(FluidTags.WATER);
    }

    /**
     * Checks whether the player is actually making progress along the path.
     * Compares the actual position delta between ticks against the expected
     * movement speed.  If the player barely moved for several consecutive
     * ticks, the server is rejecting our position packets — we're blocked.
     *
     * @return true if the player is blocked and speed should drop to minimum
     */
    private boolean checkMovementBlocked(Minecraft client) {
        if (client.player == null) return false;

        Vec3 currentPos = client.player.position();
        if (lastActualPosition == null) {
            lastActualPosition = currentPos;
            blockedTicks = 0;
            return false;
        }

        double actuallyMoved = currentPos.distanceTo(lastActualPosition);
        double expectedStep = SweepState.getSpeed() / 20.0;
        lastActualPosition = currentPos;

        // Player moved less than 30% of the expected distance → blocked this tick
        if (actuallyMoved < expectedStep * MOVE_PROGRESS_RATIO && expectedStep > 0.05) {
            blockedTicks++;
        } else {
            blockedTicks = Math.max(0, blockedTicks - 1);
        }

        return blockedTicks >= BLOCKED_TICK_THRESHOLD;
    }

    /**
     * Checks whether the player's body is currently overlapping any
     * solid block.  Used as an emergency brake — immediate drop to min speed.
     */
    private boolean checkPlayerStuck(Minecraft client) {
        if (client.player == null || client.level == null) return false;

        Vec3 pos = client.player.position();
        double h = client.player.getBbHeight();
        int step = Math.max(1, (int) (h / 3));

        for (int yOff = 0; yOff <= (int) h; yOff += step) {
            BlockPos bp = new BlockPos(
                (int) Math.floor(pos.x),
                (int) Math.floor(pos.y + yOff),
                (int) Math.floor(pos.z));
            if (!client.level.isLoaded(bp)) continue;
            BlockState state = client.level.getBlockState(bp);
            if (!state.isAir() && !state.getCollisionShape(client.level, bp).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans a cylindrical volume ahead of the player along the movement
     * direction and returns the fraction of blocks that are non-air (solid).
     *
     * @param playerPos current player position
     * @param direction unit vector in the movement direction
     * @return density ratio [0, 1] — 0 means all air, 1 means all solid
     */
    private double computeBlockDensity(Vec3 playerPos, Vec3 direction) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || direction.lengthSqr() < 1e-6) return 0.0;

        int r = SweepState.getRadius();
        double scanLength = Math.max(r * 1.5, 3.0);

        // Compute bounding box of the scan cylinder (world-aligned)
        Vec3 scanEnd = playerPos.add(direction.scale(scanLength));
        int minX = (int) Math.floor(Math.min(playerPos.x, scanEnd.x) - r);
        int maxX = (int) Math.ceil(Math.max(playerPos.x, scanEnd.x) + r);
        int minY = (int) Math.floor(Math.min(playerPos.y, scanEnd.y) - r);
        int maxY = (int) Math.ceil(Math.max(playerPos.y, scanEnd.y) + r);
        int minZ = (int) Math.floor(Math.min(playerPos.z, scanEnd.z) - r);
        int maxZ = (int) Math.ceil(Math.max(playerPos.z, scanEnd.z) + r);

        int nonAir = 0;
        int total = 0;
        // Adaptive step size: use 1-block steps for small radii, 2 for larger
        int step = r <= 4 ? 1 : 2;

        for (int x = minX; x <= maxX; x += step) {
            for (int y = minY; y <= maxY; y += step) {
                for (int z = minZ; z <= maxZ; z += step) {
                    Vec3 blockCenter = new Vec3(x + 0.5, y + 0.5, z + 0.5);

                    // Project onto direction to get distance along cylinder axis
                    Vec3 toBlock = blockCenter.subtract(playerPos);
                    double alongDist = toBlock.dot(direction);

                    // Must be within [0, scanLength] along the direction
                    if (alongDist < 0 || alongDist > scanLength) continue;

                    // Perpendicular distance from the cylinder axis
                    Vec3 projection = playerPos.add(direction.scale(alongDist));
                    double perpDist = blockCenter.distanceTo(projection);

                    if (perpDist > r) continue;

                    BlockPos bp = new BlockPos((int) Math.floor(blockCenter.x),
                        (int) Math.floor(blockCenter.y),
                        (int) Math.floor(blockCenter.z));
                    if (client.level.isLoaded(bp)) {
                        total++;
                        BlockState state = client.level.getBlockState(bp);
                        if (!state.isAir()) {
                            nonAir++;
                        }
                    }
                }
            }
        }

        if (total == 0) return 0.0;
        return (double) nonAir / total;
    }

    // --- Multi-region path building ---

    /**
     * Pre-builds path data for all sub-regions before sweeping begins.
     */
    private void buildAllRegionPaths(List<LitematicaIntegration.SubRegionBox> subRegions) {
        regions.clear();
        totalAllRegionsLength = 0.0;
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
            totalAllRegionsLength += totalLen;
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

            completedRegionsLength += regions.get(currentRegionIndex).totalLength();
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
            clearActionBar();
        }
    }

    private void error(String msg) {
        errorMessage = msg;
        state = State.ERROR;
        SweepState.setRunning(false);
        clearActionBar();
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
        completedRegionsLength = 0.0;
        totalAllRegionsLength = 0.0;
    }

    private void resetApproach() {
        approachOrigin = null;
        approachTarget = null;
        approachDistance = 0.0;
        approachTraveled = 0.0;
        lastActualPosition = null;
        blockedTicks = 0;
    }

    // --- Shared snake-path algorithm ---

    /**
     * Compute the minimum number of stations needed to cover {@code [min, max]}
     * with stations spaced by {@code spacing} where each station covers a radius
     * of {@code spacing / 1.5}.  The first station sits at {@code min}, and extra
     * stations are added only when the previous station's coverage does not reach
     * {@code max}.
     */
    private static int stationsForRange(int min, int max, double spacing) {
        double radius = spacing / 1.5;
        int extra = (int) Math.ceil(Math.max(0.0, (max - min) - radius) / spacing);
        return 1 + extra;
    }

    private static List<Vec3> buildSnakePath(int minX, int maxX, int minY, int maxY,
                                              int minZ, int maxZ, int radius) {
        double spacing = Math.max(1.0, radius * 1.5);

        int xCols = stationsForRange(minX, maxX, spacing);
        List<Integer> xStations = new ArrayList<>(xCols);
        for (int xi = 0; xi < xCols; xi++) {
            int x = minX + (int) Math.round(xi * spacing);
            xStations.add(Math.max(minX, Math.min(maxX, x)));
        }

        int zRows = stationsForRange(minZ, maxZ, spacing);
        List<Integer> zStations = new ArrayList<>(zRows);
        for (int zi = 0; zi < zRows; zi++) {
            int z = minZ + (int) Math.round(zi * spacing);
            zStations.add(Math.max(minZ, Math.min(maxZ, z)));
        }

        int yLevels = stationsForRange(minY, maxY, spacing);

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
