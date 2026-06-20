package indi.ohtoai.tool.client_tools.client.sweep;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection-based access to Litematica's area selection API.
 * No compile dependency on Litematica — everything goes through reflection.
 *
 * <p>Verified API (Litematica 0.19.57):
 * <pre>
 *   DataManager.getInstance()                     // static → singleton
 *   DataManager.getSelectionManager()             // static → SelectionManager
 *   SelectionManager.getCurrentSelection()        // → AreaSelection? (nullable)
 *   AreaSelection.getAllSubRegionBoxes()          // → List&lt;Box&gt;
 *   Box.getName(), Box.getPos1(), Box.getPos2()   // → String, BlockPos, BlockPos
 * </pre>
 */
public class LitematicaIntegration {

    public record SubRegionBox(String name, BlockPos pos1, BlockPos pos2) {}

    // --- Init state ---

    private static volatile boolean triedInit = false;
    private static volatile boolean available = false;
    private static String initError = null;

    // --- Cached reflection handles ---

    /** The DataManager singleton instance. */
    private static Object dataManagerInstance;
    /** Reflected static method: DataManager.getSelectionManager() */
    private static Method getSelectionManagerMethod;
    /** Reflected method: SelectionManager.getCurrentSelection() */
    private static Method getCurrentSelectionMethod;
    /** Reflected method: AreaSelection.getAllSubRegionBoxes() */
    private static Method getAllSubRegionBoxesMethod;
    /** Reflected method: Box.getName() — optional */
    private static Method boxGetNameMethod;
    /** Reflected method: Box.getPos1() */
    private static Method boxGetPos1Method;
    /** Reflected method: Box.getPos2() */
    private static Method boxGetPos2Method;

    // --- Move selection ---

    /** Reflected method: SelectionManager.moveSelectedElement(String, Direction, int) */
    private static Method moveSelectedElementMethod;

    private LitematicaIntegration() {}

    // --- Public API ---

    public static boolean isAvailable() {
        ensureInit();
        return available;
    }

    public static String getInitError() {
        ensureInit();
        return initError;
    }

    @SuppressWarnings("unchecked")
    public static List<SubRegionBox> getSubRegions() {
        if (!isAvailable()) return List.of();

        try {
            // SelectionManager sm = DataManager.getSelectionManager()   ← STATIC
            Object selectionManager = getSelectionManagerMethod.invoke(null);
            if (selectionManager == null) return List.of();

            // AreaSelection sel = sm.getCurrentSelection()
            Object currentSelection = getCurrentSelectionMethod.invoke(selectionManager);
            if (currentSelection == null) return List.of();

            // List<Box> boxes = sel.getAllSubRegionBoxes()
            List<?> boxes = (List<?>) getAllSubRegionBoxesMethod.invoke(currentSelection);
            if (boxes == null || boxes.isEmpty()) return List.of();

            List<SubRegionBox> result = new ArrayList<>(boxes.size());
            int idx = 0;
            for (Object box : boxes) {
                if (box == null) continue;
                idx++;
                BlockPos pos1 = (BlockPos) boxGetPos1Method.invoke(box);
                BlockPos pos2 = (BlockPos) boxGetPos2Method.invoke(box);
                if (pos1 == null || pos2 == null) continue;

                String name = "Box " + idx;
                if (boxGetNameMethod != null) {
                    try {
                        String n = (String) boxGetNameMethod.invoke(box);
                        if (n != null && !n.isEmpty()) name = n;
                    } catch (Exception ignored) {}
                }
                result.add(new SubRegionBox(name, pos1, pos2));
            }
            return result;
        } catch (IllegalAccessException | InvocationTargetException e) {
            return List.of();
        }
    }

    /**
     * Returns a lightweight fingerprint of the current selection,
     * without creating SubRegionBox objects. Used to detect changes
     * cheaply before doing a full {@link #getSubRegions()} fetch.
     *
     * @return fingerprint (0 if no selection or unavailable)
     */
    @SuppressWarnings("unchecked")
    public static long getSelectionFingerprint() {
        if (!isAvailable()) return 0;

        try {
            Object selectionManager = getSelectionManagerMethod.invoke(null);
            if (selectionManager == null) return 0;
            Object currentSelection = getCurrentSelectionMethod.invoke(selectionManager);
            if (currentSelection == null) return 0;
            List<?> boxes = (List<?>) getAllSubRegionBoxesMethod.invoke(currentSelection);
            if (boxes == null || boxes.isEmpty()) return 0;

            long fp = boxes.size();
            for (Object box : boxes) {
                if (box == null) continue;
                BlockPos p1 = (BlockPos) boxGetPos1Method.invoke(box);
                BlockPos p2 = (BlockPos) boxGetPos2Method.invoke(box);
                if (p1 != null) fp = fp * 31 + p1.hashCode();
                if (p2 != null) fp = fp * 31 + p2.hashCode();
            }
            return fp;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Moves all sub-regions of the current Litematica selection by the given offset.
     *
     * @param dx offset along the X axis (positive = east, negative = west)
     * @param dy offset along the Y axis (positive = up, negative = down)
     * @param dz offset along the Z axis (positive = south, negative = north)
     * @return the number of sub-regions moved, or -1 if the move failed
     */
    public static int moveSelection(int dx, int dy, int dz) {
        if (!isAvailable()) return -1;

        try {
            Object selectionManager = getSelectionManagerMethod.invoke(null);
            if (selectionManager == null) return -1;
            Object currentSelection = getCurrentSelectionMethod.invoke(selectionManager);
            if (currentSelection == null) return -1;
            @SuppressWarnings("unchecked")
            List<?> boxes = (List<?>) getAllSubRegionBoxesMethod.invoke(currentSelection);
            if (boxes == null || boxes.isEmpty()) return 0;

            // Apply each non-zero axis offset to every sub-region
            applyMove(selectionManager, boxes, dx, Direction.EAST, Direction.WEST);
            applyMove(selectionManager, boxes, dy, Direction.UP, Direction.DOWN);
            applyMove(selectionManager, boxes, dz, Direction.SOUTH, Direction.NORTH);

            return boxes.size();
        } catch (IllegalAccessException | InvocationTargetException e) {
            return -1;
        }
    }

    private static void applyMove(Object selectionManager, List<?> boxes,
                                   int offset, Direction posDir, Direction negDir)
            throws IllegalAccessException, InvocationTargetException {
        if (offset == 0) return;
        Direction dir = offset > 0 ? posDir : negDir;
        int amount = Math.abs(offset);
        int idx = 0;
        for (Object box : boxes) {
            if (box == null) continue;
            idx++;
            String name = "Box " + idx;
            if (boxGetNameMethod != null) {
                try {
                    String n = (String) boxGetNameMethod.invoke(box);
                    if (n != null && !n.isEmpty()) name = n;
                } catch (Exception ignored) {}
            }
            moveSelectedElementMethod.invoke(selectionManager, name, dir, amount);
        }
    }

    // --- Init ---

    private static void ensureInit() {
        if (triedInit) return;
        synchronized (LitematicaIntegration.class) {
            if (triedInit) return;
            triedInit = true;
            try {
                initReflection();
                available = true;
                initError = null;
            } catch (Exception e) {
                available = false;
                initError = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (e.getCause() != null) {
                    initError += " (cause: " + e.getCause().getClass().getSimpleName()
                        + ": " + e.getCause().getMessage() + ")";
                }
            }
        }
    }

    private static void initReflection() throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            throw new IllegalStateException("Litematica mod not loaded");
        }

        // 1. DataManager class + getInstance()
        Class<?> dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
        Method getInstanceMethod = dataManagerClass.getMethod("getInstance");
        dataManagerInstance = getInstanceMethod.invoke(null);
        if (dataManagerInstance == null) throw new IllegalStateException("DataManager instance null");

        // 2. DataManager.getSelectionManager() — STATIC method
        getSelectionManagerMethod = dataManagerClass.getMethod("getSelectionManager");
        if (!java.lang.reflect.Modifier.isStatic(getSelectionManagerMethod.getModifiers())) {
            throw new NoSuchMethodException("getSelectionManager is not static");
        }

        // 3. Call getSelectionManager() to get the SelectionManager and its class
        Object selectionManager = getSelectionManagerMethod.invoke(null);
        if (selectionManager == null) throw new IllegalStateException("SelectionManager null");
        Class<?> selectionManagerClass = selectionManager.getClass();

        // 4. SelectionManager.getCurrentSelection() → AreaSelection?
        getCurrentSelectionMethod = selectionManagerClass.getMethod("getCurrentSelection");

        // 5. Get an AreaSelection instance (may be null if no selection active)
        Object areaSelectionSample = getCurrentSelectionMethod.invoke(selectionManager);
        Class<?> areaSelectionClass;
        if (areaSelectionSample != null) {
            areaSelectionClass = areaSelectionSample.getClass();
        } else {
            areaSelectionClass = Class.forName("fi.dy.masa.litematica.selection.AreaSelection");
        }

        // 6. AreaSelection.getAllSubRegionBoxes() → List<Box>
        getAllSubRegionBoxesMethod = areaSelectionClass.getMethod("getAllSubRegionBoxes");

        // 7. Resolve Box class
        Class<?> boxClass;
        if (areaSelectionSample != null) {
            @SuppressWarnings("unchecked")
            List<?> sampleBoxes = (List<?>) getAllSubRegionBoxesMethod.invoke(areaSelectionSample);
            if (sampleBoxes != null && !sampleBoxes.isEmpty()) {
                boxClass = sampleBoxes.get(0).getClass();
            } else {
                boxClass = Class.forName("fi.dy.masa.litematica.selection.Box");
            }
        } else {
            boxClass = Class.forName("fi.dy.masa.litematica.selection.Box");
        }

        // 8. Box.getPos1(), getPos2(), getName()
        boxGetPos1Method = boxClass.getMethod("getPos1");
        boxGetPos2Method = boxClass.getMethod("getPos2");
        try {
            boxGetNameMethod = boxClass.getMethod("getName");
        } catch (NoSuchMethodException e) {
            boxGetNameMethod = null; // optional
        }

        // 9. SelectionManager.moveSelectedElement(String, Direction, int)
        moveSelectedElementMethod = selectionManagerClass.getMethod(
            "moveSelectedElement", String.class, Direction.class, int.class);
    }
}
