package indi.ohtoai.tool.client_tools.client.craft;

import indi.ohtoai.tool.client_tools.client.util.ContainerUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tick-based state machine that executes a crafting chain by interacting
 * with vanilla containers (shulker boxes + crafting table) via packets.
 *
 * The {@code count} parameter controls the maximum number of final
 * products to craft. -1 = infinite (until raw materials run out or
 * output box is full).
 *
 * In finite mode, the system calculates exactly how many source items are
 * needed and only takes that many from the input box, rather than greedily
 * emptying it. For the last partial stack in a slot, it uses single-item
 * shift-right-click (QUICK_MOVE button 1) to extract the exact number needed,
 * rather than taking the entire stack.
 *
 * For output deposit, the player's inventory is pre-scanned (client-side,
 * zero packets) to locate which slots contain the final product, so only
 * those slots are processed instead of scanning all 63 menu slots.
 *
 * Full cycle:
 *   OPEN_INPUT  → take calculated source items (precise for partial stacks) → close
 *   OPEN_TABLE  → per-step inner loop (until step target or no materials) → close
 *   OPEN_OUTPUT → deposit all final products via pre-scanned slots → close
 *   CHECK_CYCLE → target reached? → DONE
 *               → source taken?     → loop back to OPEN_INPUT
 *               → nothing taken?    → DONE (input exhausted)
 */
public class CraftingExecutor {

    private enum State {
        IDLE,
        // Input box
        OPEN_INPUT, WAIT_INPUT_OPEN, SCAN_INPUT, TAKE_INPUT, WAIT_INPUT_TAKE,
        TAKE_INPUT_PRECISE, WAIT_INPUT_PRECISE,
        CLOSE_INPUT, WAIT_INPUT_CLOSE,
        // Crafting table
        OPEN_TABLE, WAIT_TABLE_OPEN,
        CHECK_STEP_MATERIALS,
        PLACE_RECIPE, WAIT_PLACE, TAKE_OUTPUT, WAIT_TAKE,
        NEXT_STEP,
        CLOSE_TABLE, WAIT_CLOSE_TABLE,
        // Output box
        OPEN_OUTPUT, WAIT_OUTPUT_OPEN, DEPOSIT_OUTPUT, WAIT_OUTPUT_DEPOSIT, CLOSE_OUTPUT, WAIT_OUTPUT_CLOSE,
        RETRY_OUTPUT,
        // Cycle control
        CHECK_CYCLE,
        WAIT_INPUT_RETRY,
        WAIT_OUTPUT_RETRY,
        DONE, ERROR
    }

    private static CraftingExecutor instance;

    private State state = State.IDLE;
    private List<RecipeChainAnalyzer.RecipeStep> steps;
    private int currentStep = 0;
    private BlockPos stationPos;
    private BlockPos inputPos;
    private BlockPos outputPos;
    private Item finalProductItem;
    private int targetCount = -1;       // -1 = infinite, >0 = max final products to craft
    private int finalProductsMade = 0;   // cumulative final products crafted across all cycles
    private int totalCrafted = 0;        // cumulative items deposited to output (reset on start)
    private int outputBoxAttempts = 0;   // retry counter for full output box
    private int tickCounter = 0;
    private int slotIndex = 0;
    private String errorMessage;

    // Material planning
    private int[] stepOutputTargets = null;  // per-step output targets (null = unlimited)
    private int sourceItemsToTake = -1;      // max source items to take from input each cycle (-1 = unlimited)
    private int currentStepProduced = 0;     // how many outputs produced in current step this cycle

    // Cycle tracking
    private int cycleSourceTaken = 0;        // how many source items taken in current cycle
    private int cycleCount = 0;              // how many full cycles have been completed
    private int inputMissingRetries = 0;     // consecutive times input box failed to open
    private int outputMissingRetries = 0;    // consecutive times output box failed to open

    // Precise extraction (for partial stacks)
    private int preciseExtractNeeded = 0;    // remaining single items to extract from current slot
    private int preciseExtractSlot = -1;     // shulker slot being precisely extracted
    private Item preciseExtractSlotItem;     // item type being precisely extracted (auto mode)

    // Targeted deposit (pre-scanned menu slots, populated after container opens)
    private final List<Integer> productDepositSlots = new ArrayList<>(); // menu slot indices (27-62) holding product
    private int productDepositIdx = 0;       // current index into productDepositSlots
    private int inventoryBeforeDeposit = 0;  // snapshot taken before opening output box

    // Auto-detect mode (multi-source MaterialPlan)
    private boolean autoMode = false;
    private MaterialPlanner planner;
    private MaterialPlanner.MaterialPlan currentPlan;
    private Map<Item, Integer> sourceItemsNeeded;  // item → total count to take this cycle
    private Map<Item, Integer> sourceItemsTaken;   // item → count already taken

    private static final int WAIT_TICKS = 2;              // reduced from 4; safe for both local and remote servers
    private static final int CLOSE_WAIT_TICKS = 1;         // minimal wait for close packets
    private static final int CONTAINER_OPEN_TIMEOUT = 60;  // 3s — generous for high-latency servers
    private static final int MAX_MISSING_RETRIES = 3;      // retry 3× if container disappeared
    private static final int RETRY_DELAY_TICKS = 40;       // ~2s delay between retries

    private CraftingExecutor() {}

    public static CraftingExecutor getInstance() {
        if (instance == null) {
            instance = new CraftingExecutor();
        }
        return instance;
    }

    /**
     * Start executing a crafting chain (legacy mode with fixed source item).
     */
    public void start(List<RecipeChainAnalyzer.RecipeStep> steps, BlockPos stationPos,
                      BlockPos inputPos, BlockPos outputPos, Item finalProductItem,
                      int targetCount) {
        resetCommon(stationPos, inputPos, outputPos, finalProductItem, targetCount);
        this.autoMode = false;
        this.steps = steps;
        this.planner = null;
        this.currentPlan = null;
        this.sourceItemsNeeded = null;
        this.sourceItemsTaken = null;

        calculateMaterialPlan();
        this.state = State.OPEN_INPUT;
    }

    /**
     * Start executing in auto-detect mode — the input chest will be scanned
     * on each cycle and a multi-source {@link MaterialPlanner.MaterialPlan}
     * will be built automatically.
     */
    public void startAuto(Item finalProductItem, int targetCount,
                          BlockPos stationPos, BlockPos inputPos, BlockPos outputPos,
                          MaterialPlanner planner) {
        resetCommon(stationPos, inputPos, outputPos, finalProductItem, targetCount);
        this.autoMode = true;
        this.planner = planner;
        this.steps = null;
        this.currentPlan = null;
        this.sourceItemsNeeded = null;
        this.sourceItemsTaken = null;

        this.state = State.OPEN_INPUT;
    }

    private void resetCommon(BlockPos stationPos, BlockPos inputPos, BlockPos outputPos,
                             Item finalProductItem, int targetCount) {
        this.stationPos = stationPos;
        this.inputPos = inputPos;
        this.outputPos = outputPos;
        this.finalProductItem = finalProductItem;
        this.targetCount = targetCount;
        this.currentStep = 0;
        this.finalProductsMade = 0;
        this.totalCrafted = 0;
        this.outputBoxAttempts = 0;
        this.tickCounter = 0;
        this.slotIndex = 0;
        this.currentStepProduced = 0;
        this.sourceItemsToTake = -1;
        this.cycleSourceTaken = 0;
        this.cycleCount = 0;
        this.inputMissingRetries = 0;
        this.outputMissingRetries = 0;
        this.preciseExtractNeeded = 0;
        this.preciseExtractSlot = -1;
        this.preciseExtractSlotItem = null;
        this.productDepositSlots.clear();
        this.productDepositIdx = 0;
        this.inventoryBeforeDeposit = 0;
        this.errorMessage = null;
    }

    /** Stops the executor immediately. */
    public void stop() {
        if (!isRunning()) return;
        this.state = State.IDLE;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.containerMenu != null
            && client.player.connection != null) {
            client.player.connection.send(
                new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(
                    client.player.containerMenu.containerId));
        }
    }

    /** Called every client tick. */
    public void tick(Minecraft client) {
        if (state == State.IDLE || state == State.DONE || state == State.ERROR) return;

        tickCounter++;

        switch (state) {
            // Input box
            case OPEN_INPUT -> doOpenInput(client);
            case WAIT_INPUT_OPEN -> doWaitInputOpen(client);
            case SCAN_INPUT -> doScanInput(client);
            case TAKE_INPUT -> doTakeInput(client);
            case WAIT_INPUT_TAKE -> doWaitInputTake(client);
            case TAKE_INPUT_PRECISE -> doTakeInputPrecise(client);
            case WAIT_INPUT_PRECISE -> doWaitInputPrecise(client);
            case CLOSE_INPUT -> doCloseContainer(client, State.WAIT_INPUT_CLOSE);
            case WAIT_INPUT_CLOSE -> doWaitClose(client, State.OPEN_TABLE, CLOSE_WAIT_TICKS);
            // Crafting table
            case OPEN_TABLE -> doOpenTable(client);
            case WAIT_TABLE_OPEN -> doWaitTableOpen(client);
            case CHECK_STEP_MATERIALS -> doCheckStepMaterials(client);
            case PLACE_RECIPE -> doPlaceRecipe(client);
            case WAIT_PLACE -> doWaitPlace(client);
            case TAKE_OUTPUT -> doTakeOutput(client);
            case WAIT_TAKE -> doWaitTake(client);
            case NEXT_STEP -> doNextStep(client);
            case CLOSE_TABLE -> doCloseContainer(client, State.WAIT_CLOSE_TABLE);
            case WAIT_CLOSE_TABLE -> doWaitClose(client, State.OPEN_OUTPUT, WAIT_TICKS);
            // Output box
            case OPEN_OUTPUT -> doOpenOutput(client);
            case WAIT_OUTPUT_OPEN -> doWaitOutputOpen(client);
            case DEPOSIT_OUTPUT -> doDepositOutput(client);
            case WAIT_OUTPUT_DEPOSIT -> doWaitOutputDeposit(client);
            case CLOSE_OUTPUT -> doCloseContainer(client, State.WAIT_OUTPUT_CLOSE);
            case WAIT_OUTPUT_CLOSE -> doWaitClose(client, State.RETRY_OUTPUT, CLOSE_WAIT_TICKS);
            case RETRY_OUTPUT -> doRetryOutput(client);
            // Cycle control
            case CHECK_CYCLE -> doCheckCycle(client);
            case WAIT_INPUT_RETRY -> doWaitInputRetry(client);
            case WAIT_OUTPUT_RETRY -> doWaitOutputRetry(client);
            default -> {}
        }
    }

    public boolean isRunning() {
        return state != State.IDLE && state != State.DONE && state != State.ERROR;
    }

    public boolean isDone() {
        return state == State.DONE;
    }

    public boolean isError() {
        return state == State.ERROR;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getFinalProductsMade() {
        return finalProductsMade;
    }

    public int getTotalCrafted() {
        return totalCrafted;
    }

    public int getCycleCount() {
        return cycleCount;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public int getCurrentStepIndex() {
        return currentStep;
    }

    public int getCurrentStepProgress() {
        return currentStepProduced;
    }

    public int getSourceTaken() {
        return cycleSourceTaken;
    }

    public int getStepCount() {
        if (autoMode && currentPlan != null) return currentPlan.operations.size();
        return steps != null ? steps.size() : 0;
    }

    // ==================== Material Planning ====================

    /**
     * Calculates per-step output targets and exact source item count needed,
     * accounting for existing inventory items (final products, intermediates,
     * source items). In infinite mode, no targets are set (craft until exhaustion).
     *
     * After this call, {@link #sourceItemsToTake} holds the maximum number of
     * source items to extract from the input box. In finite mode this is the
     * precise number needed; in infinite mode it is -1 (unlimited).
     */
    private void calculateMaterialPlan() {
        if (targetCount <= 0 || steps.isEmpty()) {
            stepOutputTargets = null;
            sourceItemsToTake = -1; // unlimited
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            stepOutputTargets = null;
            sourceItemsToTake = -1;
            return;
        }

        stepOutputTargets = new int[steps.size()];

        // How many final products we need to MAKE (after accounting for existing)
        int needToMake = Math.max(0, targetCount - countItemInInventory(client, finalProductItem));

        if (needToMake == 0) {
            // Already have enough — all step targets are 0 (they will be skipped)
            for (int i = 0; i < steps.size(); i++) {
                stepOutputTargets[i] = 0;
            }
            sourceItemsToTake = 0;
            return;
        }

        int neededOutput = needToMake;

        // Walk backwards through the chain
        for (int i = steps.size() - 1; i >= 0; i--) {
            RecipeChainAnalyzer.RecipeStep step = steps.get(i);

            // Subtract existing intermediates (for non-last steps)
            if (i < steps.size() - 1) {
                neededOutput = Math.max(0, neededOutput - countItemInInventory(client, step.toItem()));
            }

            if (neededOutput == 0) {
                stepOutputTargets[i] = 0;
                if (i > 0) neededOutput = 0;
            } else {
                int executions = ceilDiv(neededOutput, step.toCount());
                stepOutputTargets[i] = executions * step.toCount();
                int neededInput = executions * step.fromCount();

                if (i > 0) {
                    neededOutput = neededInput;
                }
            }
        }

        // Calculate exact source items to take for the first step
        calculateSourceItemsToTake(client);
    }

    /**
     * Calculates how many source items to take from the input box,
     * based on step 0's output target and existing inventory.
     */
    private void calculateSourceItemsToTake(Minecraft client) {
        if (stepOutputTargets == null || steps.isEmpty()) {
            sourceItemsToTake = -1; // unlimited
            return;
        }

        int target = stepOutputTargets[0];
        if (target == 0) {
            sourceItemsToTake = 0;
            return;
        }

        RecipeChainAnalyzer.RecipeStep firstStep = steps.get(0);
        // target is already a multiple of toCount, so this division is exact
        int executions = target / firstStep.toCount();
        int totalNeeded = executions * firstStep.fromCount();
        Item sourceItem = firstStep.fromItem();
        int alreadyHave = countItemInInventory(client, sourceItem);
        sourceItemsToTake = Math.max(0, totalNeeded - alreadyHave);
    }

    /**
     * Recalculates step targets after a crafting cycle, adjusting for what
     * was already produced in previous cycles.
     */
    private void recalcStepTargets() {
        if (targetCount <= 0 || steps.isEmpty() || stepOutputTargets == null) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        // How many more final products do we need to make?
        // totalCrafted tracks items already deposited to output box.
        int existingFinal = countItemInInventory(client, finalProductItem) + totalCrafted;
        int needToMake = Math.max(0, targetCount - existingFinal);

        if (needToMake == 0) {
            for (int i = 0; i < stepOutputTargets.length; i++) {
                stepOutputTargets[i] = 0;
            }
            sourceItemsToTake = 0;
            return;
        }

        int neededOutput = needToMake;
        for (int i = steps.size() - 1; i >= 0; i--) {
            RecipeChainAnalyzer.RecipeStep step = steps.get(i);

            if (i < steps.size() - 1) {
                neededOutput = Math.max(0, neededOutput - countItemInInventory(client, step.toItem()));
            }

            if (neededOutput == 0) {
                stepOutputTargets[i] = 0;
                if (i > 0) neededOutput = 0;
            } else {
                int executions = ceilDiv(neededOutput, step.toCount());
                stepOutputTargets[i] = executions * step.toCount();
                int neededInput = executions * step.fromCount();

                if (i > 0) {
                    neededOutput = neededInput;
                }
            }
        }

        calculateSourceItemsToTake(client);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private int countItemInInventory(Minecraft client, Item item) {
        if (client.player == null) return 0;
        int count = 0;
        for (ItemStack stack : client.player.getInventory().items) {
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    // ==================== Input Box ====================

    private void doOpenInput(Minecraft client) {
        if (!checkPlayer(client)) return;
        cycleSourceTaken = 0;
        openBlock(client, inputPos);
        state = State.WAIT_INPUT_OPEN;
        tickCounter = 0;
    }

    private void doWaitInputOpen(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (ContainerUtils.getContainerSize(client.player.containerMenu) >= 0) {
            // Input box opened successfully — reset missing counter
            inputMissingRetries = 0;
            if (autoMode) {
                // Auto-detect mode: scan chest contents first, then build plan
                state = State.SCAN_INPUT;
                tickCounter = 0;
            } else if (sourceItemsToTake == 0) {
                // Nothing needed this cycle — close immediately
                state = State.CLOSE_INPUT;
            } else {
                state = State.TAKE_INPUT;
                slotIndex = 0;
            }
            tickCounter = 0;
            return;
        }
        if (tickCounter > CONTAINER_OPEN_TIMEOUT) {
            if (targetCount <= 0) {
                // Infinite mode: input box may be temporarily gone (being replaced by unpacker).
                // Increment the missing counter and wait before retrying.
                inputMissingRetries++;
                state = State.WAIT_INPUT_RETRY;
                tickCounter = 0;
            } else {
                error("Timed out waiting for input container to open");
            }
        }
    }

    /**
     * Auto-mode: scan the input chest contents and build a multi-source
     * MaterialPlan. This runs once per cycle right after the input box opens.
     * Results are stored in {@link #currentPlan}, {@link #sourceItemsNeeded},
     * and {@link #sourceItemsTaken} for use by the take/craft phases.
     */
    private void doScanInput(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int containerSize = ContainerUtils.getContainerSize(menu);
        if (containerSize < 0) {
            error("Input container not open");
            return;
        }

        // Scan all container slots and build a chest-items map
        Map<Item, Integer> chestItems = new HashMap<>();
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                chestItems.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        if (chestItems.isEmpty()) {
            // Chest is empty — nothing to do this cycle
            cycleSourceTaken = 0;
            sourceItemsNeeded = null;
            sourceItemsTaken = null;
            currentPlan = null;
            state = State.CLOSE_INPUT;
            tickCounter = 0;
            return;
        }

        // Build the material plan
        currentPlan = planner.buildPlan(finalProductItem, targetCount, chestItems);
        if (currentPlan == null || currentPlan.isEmpty()) {
            // No craftable path found from chest items
            cycleSourceTaken = 0;
            sourceItemsNeeded = null;
            sourceItemsTaken = null;
            state = State.CLOSE_INPUT;
            tickCounter = 0;
            return;
        }

        // Initialize take tracking
        sourceItemsNeeded = new HashMap<>(currentPlan.takeItems);
        sourceItemsTaken = new HashMap<>();

        // Pre-count items already in player inventory (from previous cycles)
        cycleSourceTaken = 0;
        for (var entry : sourceItemsNeeded.entrySet()) {
            int alreadyInInv = countItemInInventory(client, entry.getKey());
            int effectiveNeed = Math.max(0, entry.getValue() - alreadyInInv);
            if (effectiveNeed < entry.getValue()) {
                sourceItemsNeeded.put(entry.getKey(), effectiveNeed);
            }
        }

        // Check if we actually need anything from the chest
        boolean needAnything = false;
        for (int needed : sourceItemsNeeded.values()) {
            if (needed > 0) { needAnything = true; break; }
        }

        if (!needAnything) {
            // Everything needed is already in inventory
            state = State.CLOSE_INPUT;
            tickCounter = 0;
        } else {
            state = State.TAKE_INPUT;
            slotIndex = 0;
            tickCounter = 0;
        }
    }

    /**
     * Takes source items from the input box, stopping when enough have been
     * taken (finite mode) or all slots have been scanned (infinite mode).
     * Tracks how many were taken this cycle (cycleSourceTaken).
     *
     * For the last partial stack (where the slot has more items than we need),
     * delegates to {@link #doTakeInputPrecise} which extracts exact count via
     * single-item shift-right-clicks.
     */
    private void doTakeInput(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int containerSize = ContainerUtils.getContainerSize(menu);
        if (containerSize < 0) {
            error("Input container not open");
            return;
        }

        if (autoMode) {
            doTakeInputAuto(client, menu, containerSize);
        } else {
            doTakeInputLegacy(client, menu, containerSize);
        }
    }

    /**
     * Legacy single-source-item extraction.
     */
    private void doTakeInputLegacy(Minecraft client, AbstractContainerMenu menu, int containerSize) {
        Item sourceItem = steps.get(0).fromItem();

        if (slotIndex < containerSize) {
            if (sourceItemsToTake > 0 && cycleSourceTaken >= sourceItemsToTake) {
                state = State.CLOSE_INPUT;
                tickCounter = 0;
                return;
            }

            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (stack.getItem() == sourceItem) {
                int stackCount = stack.getCount();
                int need = sourceItemsToTake > 0 ? sourceItemsToTake - cycleSourceTaken : Integer.MAX_VALUE;

                if (need < stackCount) {
                    preciseExtractNeeded = need;
                    preciseExtractSlot = slotIndex;
                    state = State.TAKE_INPUT_PRECISE;
                    tickCounter = 0;
                } else {
                    quickMove(client, menu.containerId, slotIndex);
                    cycleSourceTaken += stackCount;
                    slotIndex++;
                    tickCounter = 0;
                    state = State.WAIT_INPUT_TAKE;
                }
            } else {
                slotIndex++;
                tickCounter = 0;
                state = State.WAIT_INPUT_TAKE;
            }
        } else {
            state = State.CLOSE_INPUT;
            tickCounter = 0;
        }
    }

    /**
     * Auto-mode multi-item extraction: take items matching any entry in
     * {@link #sourceItemsNeeded} until all needed counts are satisfied
     * or all slots have been scanned.
     */
    private void doTakeInputAuto(Minecraft client, AbstractContainerMenu menu, int containerSize) {
        if (slotIndex < containerSize) {
            // Check if all needed items are satisfied
            boolean allDone = true;
            for (var entry : sourceItemsNeeded.entrySet()) {
                int taken = sourceItemsTaken.getOrDefault(entry.getKey(), 0);
                if (taken < entry.getValue()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                state = State.CLOSE_INPUT;
                tickCounter = 0;
                return;
            }

            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                int needed = sourceItemsNeeded.getOrDefault(item, -1);
                if (needed > 0) {
                    int taken = sourceItemsTaken.getOrDefault(item, 0);
                    int remaining = needed - taken;
                    if (remaining > 0) {
                        int stackCount = stack.getCount();
                        if (remaining < stackCount) {
                            // Partial stack extraction
                            preciseExtractNeeded = remaining;
                            preciseExtractSlot = slotIndex;
                            preciseExtractSlotItem = item;
                            state = State.TAKE_INPUT_PRECISE;
                            tickCounter = 0;
                            return;
                        } else {
                            // Take entire stack
                            quickMove(client, menu.containerId, slotIndex);
                            sourceItemsTaken.merge(item, stackCount, Integer::sum);
                            cycleSourceTaken += stackCount;
                            slotIndex++;
                            tickCounter = 0;
                            state = State.WAIT_INPUT_TAKE;
                            return;
                        }
                    }
                }
            }
            // Slot didn't match or already satisfied — skip
            slotIndex++;
            tickCounter = 0;
            state = State.WAIT_INPUT_TAKE;
        } else {
            state = State.CLOSE_INPUT;
            tickCounter = 0;
        }
    }

    /**
     * Extracts a single item from the shulker slot via shift-right-click
     * (QUICK_MOVE button 1). Repeats until {@link #preciseExtractNeeded}
     * reaches zero, then continues to the next slot.
     */
    private void doTakeInputPrecise(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        if (ContainerUtils.getContainerSize(menu) < 0) {
            error("Input container not open");
            return;
        }

        if (preciseExtractNeeded <= 0) {
            state = State.TAKE_INPUT;
            tickCounter = 0;
            return;
        }

        quickMoveSingle(client, menu.containerId, preciseExtractSlot);
        cycleSourceTaken++;
        preciseExtractNeeded--;
        // Track per-item-type count in auto mode
        if (autoMode && preciseExtractSlotItem != null) {
            sourceItemsTaken.merge(preciseExtractSlotItem, 1, Integer::sum);
        }
        tickCounter = 0;
        state = State.WAIT_INPUT_PRECISE;
    }

    private void doWaitInputPrecise(Minecraft client) {
        if (tickCounter >= 1) {
            if (preciseExtractNeeded > 0) {
                state = State.TAKE_INPUT_PRECISE;
            } else {
                // Done with this slot, move to next
                slotIndex++;
                preciseExtractSlotItem = null;
                state = State.TAKE_INPUT;
            }
            tickCounter = 0;
        }
    }

    private void doWaitInputTake(Minecraft client) {
        if (tickCounter >= 1) {
            state = State.TAKE_INPUT;
            tickCounter = 0;
        }
    }

    // ==================== Crafting Table ====================

    private void doOpenTable(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (client.player.blockPosition().distSqr(stationPos) > 36) {
            error("Too far from crafting table");
            return;
        }
        openBlock(client, stationPos);
        state = State.WAIT_TABLE_OPEN;
        tickCounter = 0;
    }

    private void doWaitTableOpen(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (client.player.containerMenu instanceof CraftingMenu) {
            // Reset per-cycle crafting state
            currentStep = 0;
            currentStepProduced = 0;
            state = State.CHECK_STEP_MATERIALS;
            tickCounter = 0;
            return;
        }
        if (tickCounter > CONTAINER_OPEN_TIMEOUT) error("Timed out waiting for crafting table to open");
    }

    /**
     * Check if we should continue crafting the current step.
     * Stops when either (a) the step's output target is reached,
     * or (b) the player runs out of input materials.
     */
    private void doCheckStepMaterials(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (!(client.player.containerMenu instanceof CraftingMenu)) {
            error("Crafting table not open");
            return;
        }

        if (autoMode) {
            doCheckStepMaterialsAuto(client);
            return;
        }

        // Check step output target (finite mode)
        if (stepOutputTargets != null
            && currentStep >= 0 && currentStep < stepOutputTargets.length) {
            int target = stepOutputTargets[currentStep];
            if (target == 0 || currentStepProduced >= target) {
                state = State.NEXT_STEP;
                tickCounter = 0;
                return;
            }
        }

        // Check if we have enough materials for one more execution
        RecipeChainAnalyzer.RecipeStep step = steps.get(currentStep);
        if (hasEnoughForStep(client, step)) {
            state = State.PLACE_RECIPE;
            tickCounter = 0;
        } else {
            state = State.NEXT_STEP;
            tickCounter = 0;
        }
    }

    private void doCheckStepMaterialsAuto(Minecraft client) {
        if (currentPlan == null || currentPlan.operations.isEmpty()) {
            state = State.CLOSE_TABLE;
            tickCounter = 0;
            return;
        }

        if (currentStep >= currentPlan.operations.size()) {
            state = State.CLOSE_TABLE;
            tickCounter = 0;
            return;
        }

        MaterialPlanner.CraftOp op = currentPlan.operations.get(currentStep);
        int targetOutputs = op.executions() * op.toCount();

        if (currentStepProduced >= targetOutputs) {
            state = State.NEXT_STEP;
            tickCounter = 0;
            return;
        }

        // Check if we have enough of the fromItem in player inventory
        if (hasEnoughForOp(client, op)) {
            state = State.PLACE_RECIPE;
            tickCounter = 0;
        } else {
            state = State.NEXT_STEP;
            tickCounter = 0;
        }
    }

    private void doPlaceRecipe(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (!(client.player.containerMenu instanceof CraftingMenu)) {
            error("Crafting table not open");
            return;
        }

        ResourceLocation recipeId;
        if (autoMode) {
            // Belt-and-suspenders: check target before placing
            if (currentStep >= currentPlan.operations.size()) {
                state = State.NEXT_STEP;
                tickCounter = 0;
                return;
            }
            MaterialPlanner.CraftOp op = currentPlan.operations.get(currentStep);
            if (currentStepProduced >= op.executions() * op.toCount()) {
                state = State.NEXT_STEP;
                tickCounter = 0;
                return;
            }
            recipeId = op.recipeId();
        } else {
            // Belt-and-suspenders: check target before placing
            if (stepOutputTargets != null
                && currentStep >= 0 && currentStep < stepOutputTargets.length) {
                if (currentStepProduced >= stepOutputTargets[currentStep]) {
                    state = State.NEXT_STEP;
                    tickCounter = 0;
                    return;
                }
            }
            recipeId = steps.get(currentStep).recipeId();
        }

        var optHolder = client.level.getRecipeManager().byKey(recipeId);
        if (optHolder.isEmpty()) {
            error("Recipe not found: " + recipeId);
            return;
        }
        client.player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket(
                client.player.containerMenu.containerId, optHolder.get(), true
            )
        );
        state = State.WAIT_PLACE;
        tickCounter = 0;
    }

    private void doWaitPlace(Minecraft client) {
        if (tickCounter >= WAIT_TICKS) {
            state = State.TAKE_OUTPUT;
            tickCounter = 0;
        }
    }

    private void doTakeOutput(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (!(client.player.containerMenu instanceof CraftingMenu)) {
            error("Crafting table not open");
            return;
        }
        quickMove(client, client.player.containerMenu.containerId, 0);
        state = State.WAIT_TAKE;
        tickCounter = 0;
    }

    private void doWaitTake(Minecraft client) {
        if (tickCounter >= WAIT_TICKS) {
            int outputCount;
            if (autoMode) {
                MaterialPlanner.CraftOp op = currentPlan.operations.get(currentStep);
                outputCount = op.toCount();
                currentStepProduced += outputCount;
                if (isLastStep()) {
                    finalProductsMade += outputCount;
                }
            } else {
                RecipeChainAnalyzer.RecipeStep step = steps.get(currentStep);
                outputCount = step.toCount();
                if (stepOutputTargets != null) {
                    currentStepProduced += outputCount;
                }
                if (isLastStep()) {
                    finalProductsMade += outputCount;
                }
            }

            state = State.CHECK_STEP_MATERIALS;
            tickCounter = 0;
        }
    }

    private void doNextStep(Minecraft client) {
        currentStepProduced = 0;
        currentStep++;
        int totalSteps = autoMode ? currentPlan.operations.size() : steps.size();
        if (currentStep >= totalSteps) {
            state = State.CLOSE_TABLE;
        } else {
            state = State.CHECK_STEP_MATERIALS;
        }
        tickCounter = 0;
    }

    private boolean isLastStep() {
        if (autoMode && currentPlan != null) {
            return currentStep == currentPlan.operations.size() - 1;
        }
        return currentStep == steps.size() - 1;
    }

    // ==================== Output Box ====================

    /**
     * Opens the output shulker box. Uses a quick client-side check to skip
     * opening entirely if there are no final products in the player inventory.
     * The actual menu-slot mapping is built in {@link #doWaitOutputOpen} once
     * the container is open, since menu slot indices differ from player
     * inventory indices.
     */
    private void doOpenOutput(Minecraft client) {
        if (!checkPlayer(client)) return;

        // Quick client-side check: any products in inventory?
        // If not, skip the open/close cycle entirely.
        if (!playerHasFinalProduct(client)) {
            state = State.CHECK_CYCLE;
            tickCounter = 0;
            return;
        }

        openBlock(client, outputPos);
        state = State.WAIT_OUTPUT_OPEN;
        tickCounter = 0;
    }

    /**
     * Once the output shulker box is open, pre-scans the actual menu slots
     * (indices 27–62, which display the player inventory within the shulker
     * screen) and records which ones contain the final product. This avoids
     * the player-inventory-index → menu-slot-index mapping problem.
     */
    private void doWaitOutputOpen(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int containerSize = ContainerUtils.getContainerSize(menu);
        if (containerSize >= 0) {
            // Output box opened successfully — reset missing counter
            outputMissingRetries = 0;
            // Build the list of menu slot indices that hold the final product.
            // Player inventory slots in any storage container start right after
            // the container's own slots, and there are always 36 of them.
            productDepositSlots.clear();
            productDepositIdx = 0;
            inventoryBeforeDeposit = countItemInInventory(client, finalProductItem);
            int playerStart = containerSize;        // first player inventory slot
            int totalSlots = playerStart + 36;      // 36 = player inventory
            for (int menuSlot = playerStart; menuSlot < totalSlots; menuSlot++) {
                if (menu.getSlot(menuSlot).getItem().getItem() == finalProductItem) {
                    productDepositSlots.add(menuSlot); // store direct menu slot index
                }
            }
            state = State.DEPOSIT_OUTPUT;
            tickCounter = 0;
            return;
        }
        if (tickCounter > CONTAINER_OPEN_TIMEOUT) {
            if (targetCount <= 0) {
                // Infinite mode: output box may be temporarily gone (being replaced by packer).
                // Increment the missing counter and wait before retrying.
                outputMissingRetries++;
                state = State.WAIT_OUTPUT_RETRY;
                tickCounter = 0;
            } else {
                error("Timed out waiting for output container to open");
            }
        }
    }

    /**
     * Deposits final products into the output box by only iterating over
     * the pre-scanned menu slot indices that were known to contain the
     * product, rather than scanning all 63 menu slots. Drastically reduces
     * the number of packets needed.
     */
    private void doDepositOutput(Minecraft client) {
        if (!checkPlayer(client)) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        if (ContainerUtils.getContainerSize(menu) < 0) {
            error("Output container not open");
            return;
        }

        if (productDepositIdx < productDepositSlots.size()) {
            int menuSlot = productDepositSlots.get(productDepositIdx); // direct menu slot index
            // Bounds check: the container may have changed since slots were scanned
            // (e.g. shulker box closed/removed, falling back to player inventory with fewer slots)
            if (menuSlot >= menu.slots.size()) {
                state = State.RETRY_OUTPUT;
                tickCounter = 0;
                return;
            }
            ItemStack stack = menu.getSlot(menuSlot).getItem();
            if (stack.getItem() == finalProductItem) {
                quickMove(client, menu.containerId, menuSlot);
            }
            productDepositIdx++;
            tickCounter = 0;
            state = State.WAIT_OUTPUT_DEPOSIT;
        } else {
            state = State.CLOSE_OUTPUT;
            tickCounter = 0;
        }
    }

    private void doWaitOutputDeposit(Minecraft client) {
        if (tickCounter >= 1) {
            state = State.DEPOSIT_OUTPUT;
            tickCounter = 0;
        }
    }

    // ==================== Retry Output ====================

    /**
     * After closing the output box, check if items were deposited.
     * If the player still has final products, the output box is likely full.
     * In infinite mode: wait and retry forever (the box will eventually drain).
     * In finite mode: retry up to 3 times, then give up.
     * If no more final products, proceed to CHECK_CYCLE.
     */
    private void doRetryOutput(Minecraft client) {
        if (!checkPlayer(client)) return;

        if (playerHasFinalProduct(client)) {
            outputBoxAttempts++; // count retries, NOT initial opens
            if (targetCount <= 0) {
                // Infinite mode: output box is full, wait and retry forever.
                // (outputMissingRetries is 0 because the box DID open successfully,
                // so doWaitOutputRetry will keep retrying.)
                state = State.WAIT_OUTPUT_RETRY;
                tickCounter = 0;
                return;
            }
            if (outputBoxAttempts >= 3) {
                client.player.displayClientMessage(
                    Component.translatable("client-tools.ccraft.output_box_full", outputBoxAttempts), false);
                state = State.DONE;
                return;
            }
            // Retry opening output box (will re-scan inventory)
            state = State.OPEN_OUTPUT;
            tickCounter = 0;
        } else {
            // Successfully deposited everything — count what was actually moved
            int itemsNow = countItemInInventory(client, finalProductItem);
            int deposited = inventoryBeforeDeposit - itemsNow;
            if (deposited > 0) {
                totalCrafted += deposited;
            }
            // Reset retry counter for next cycle
            outputBoxAttempts = 0;
            state = State.CHECK_CYCLE;
            tickCounter = 0;
        }
    }

    // ==================== Cycle Control ====================

    /**
     * Waits a short delay before retrying the input box. In infinite mode:
     * - If the box opened but was empty → retry forever (input box is still there,
     *   just waiting for hoppers to feed more items).
     * - If the box failed to open (timeout, probably broken/replaced) → retry up
     *   to MAX_MISSING_RETRIES, then stop.
     */
    private void doWaitInputRetry(Minecraft client) {
        if (tickCounter >= RETRY_DELAY_TICKS) {
            if (targetCount <= 0 && inputMissingRetries >= MAX_MISSING_RETRIES) {
                // Input box has been missing for too long — no more raw materials
                sendDoneMessage(client);
                state = State.DONE;
            } else {
                state = State.OPEN_INPUT;
                tickCounter = 0;
            }
        }
    }

    /**
     * Waits a short delay before retrying the output box. In infinite mode:
     * - If the box opened but was full → retry forever (output box is still there,
     *   just waiting for hoppers to drain items).
     * - If the box failed to open (timeout, probably broken/replaced) → retry up
     *   to MAX_MISSING_RETRIES, then stop.
     */
    private void doWaitOutputRetry(Minecraft client) {
        if (tickCounter >= RETRY_DELAY_TICKS) {
            if (targetCount <= 0 && outputMissingRetries >= MAX_MISSING_RETRIES) {
                // Output box has been missing for too long — no more empty boxes
                sendDoneMessage(client);
                state = State.DONE;
            } else {
                state = State.OPEN_OUTPUT;
                tickCounter = 0;
            }
        }
    }

    /**
     * After a complete input→craft→output cycle, decides whether to:
     * - Loop back to input (target not yet reached, source was available)
     * - Stop (target reached, or input exhausted, or safety limit hit)
     */
    private void doCheckCycle(Minecraft client) {
        if (!checkPlayer(client)) return;

        cycleCount++;

        if (targetCount <= 0) {
            // Infinite mode: loop while source items were found.
            // If the input box opened but was empty → wait and retry forever
            // (the box is still there, just waiting for hoppers to feed it).
            if (cycleSourceTaken > 0) {
                inputMissingRetries = 0; // reset: box opened successfully this cycle
                outputBoxAttempts = 0;
                state = State.OPEN_INPUT;
                tickCounter = 0;
            } else {
                // Box opened but was empty — inputMissingRetries is 0 (was reset
                // when the box opened), so doWaitInputRetry will retry forever.
                outputBoxAttempts = 0;
                state = State.WAIT_INPUT_RETRY;
                tickCounter = 0;
            }
        } else {
            // Finite mode: check if target reached
            // totalCrafted tracks items already deposited to output box,
            // countItemInInventory tracks items still in player inventory.
            int existingFinal = countItemInInventory(client, finalProductItem) + totalCrafted;
            if (existingFinal >= targetCount) {
                // We have enough — but try to deposit them first if we haven't
                if (playerHasFinalProduct(client)) {
                    // Still have items to deposit, go back to output
                    outputBoxAttempts = 0; // reset retry counter for fresh deposit session
                    state = State.OPEN_OUTPUT;
                    tickCounter = 0;
                    return;
                }
                sendDoneMessage(client);
                state = State.DONE;
                return;
            }

            // Target not reached
            if (cycleSourceTaken > 0 || sourceItemsToTake == 0 ||
                (autoMode && currentPlan != null && !currentPlan.isEmpty())) {
                // We either took source items this cycle, or we skipped taking
                // because we already had enough in inventory. Recalc and loop.
                if (!autoMode) {
                    recalcStepTargets();
                }
                outputBoxAttempts = 0; // reset retry counter for new cycle
                state = State.OPEN_INPUT;
                tickCounter = 0;
            } else {
                // Input box is truly empty — we tried to take but got nothing
                client.player.displayClientMessage(
                    Component.translatable("client-tools.ccraft.input_exhausted", finalProductsMade, targetCount), false);
                sendDoneMessage(client);
                state = State.DONE;
            }
        }
    }

    private void sendDoneMessage(Minecraft client) {
        if (client.player != null) {
            if (totalCrafted > 0) {
                client.player.displayClientMessage(
                    Component.translatable("client-tools.ccraft.done_deposited", totalCrafted), false);
            } else if (finalProductsMade > 0) {
                client.player.displayClientMessage(
                    Component.translatable("client-tools.ccraft.done_made", finalProductsMade), false);
            } else {
                client.player.displayClientMessage(
                    Component.translatable("client-tools.ccraft.done_enough"), false);
            }
        }
    }

    // ==================== Helpers ====================

    private void doCloseContainer(Minecraft client, State nextState) {
        if (client.player == null || client.player.connection == null) {
            state = (nextState == State.DONE ? State.DONE : nextState);
            return;
        }
        client.player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(
                client.player.containerMenu.containerId
            )
        );
        this.state = nextState;
        tickCounter = 0;
    }

    private void doWaitClose(Minecraft client, State nextState, int waitTicks) {
        if (tickCounter >= waitTicks) {
            state = nextState;
            tickCounter = 0;
        }
    }

    private void openBlock(Minecraft client, BlockPos pos) {
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        BlockHitResult hitResult = new BlockHitResult(blockCenter, Direction.UP, pos, false);
        client.player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
                InteractionHand.MAIN_HAND, hitResult, 0
            )
        );
    }

    /**
     * Shift-left-click: moves the entire stack to the appropriate inventory area.
     */
    private void quickMove(Minecraft client, int containerId, int slot) {
        int stateId = ContainerUtils.getContainerStateId(client.player.containerMenu);
        client.player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                containerId, stateId, slot, 0,
                ClickType.QUICK_MOVE, ItemStack.EMPTY, new Int2ObjectArrayMap<>()
            )
        );
    }

    /**
     * Shift-right-click: moves exactly ONE item to the appropriate inventory area.
     * Used for precise extraction from a slot that has more items than needed.
     */
    private void quickMoveSingle(Minecraft client, int containerId, int slot) {
        int stateId = ContainerUtils.getContainerStateId(client.player.containerMenu);
        client.player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                containerId, stateId, slot, 1,  // button 1 = right-click
                ClickType.QUICK_MOVE, ItemStack.EMPTY, new Int2ObjectArrayMap<>()
            )
        );
    }

    private boolean hasEnoughForStep(Minecraft client, RecipeChainAnalyzer.RecipeStep step) {
        int needed = step.fromCount();
        Item neededItem = step.fromItem();
        int found = 0;
        for (ItemStack stack : client.player.getInventory().items) {
            if (stack.getItem() == neededItem) {
                found += stack.getCount();
                if (found >= needed) return true;
            }
        }
        return false;
    }

    private boolean hasEnoughForOp(Minecraft client, MaterialPlanner.CraftOp op) {
        int needed = op.fromCount();
        Item neededItem = op.fromItem();
        int found = 0;
        for (ItemStack stack : client.player.getInventory().items) {
            if (stack.getItem() == neededItem) {
                found += stack.getCount();
                if (found >= needed) return true;
            }
        }
        return false;
    }

    private boolean playerHasFinalProduct(Minecraft client) {
        for (ItemStack stack : client.player.getInventory().items) {
            if (stack.getItem() == finalProductItem) return true;
        }
        return false;
    }

    private boolean checkPlayer(Minecraft client) {
        if (client.player == null || client.player.connection == null) {
            error("Player not available");
            return false;
        }
        return true;
    }

    private void error(String msg) {
        this.errorMessage = msg;
        this.state = State.ERROR;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(Component.translatable("client-tools.ccraft.error", msg), false);
        }
    }

    // ==================== Static utilities ====================

    // getContainerSize and getContainerStateId are now in ContainerUtils (shared utility).

    /**
     * Scan for the nearest crafting table within range.
     */
    public static BlockPos findNearestCraftingTable(Minecraft client, BlockPos nearPos, int maxRadius) {
        if (client.level == null) return null;

        BlockPos searchCenter = nearPos != null ? nearPos : client.player.blockPosition();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        BlockPos closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (int y = -4; y <= 4; y++) {
            for (int x = -maxRadius; x <= maxRadius; x++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    mutable.set(searchCenter.getX() + x, searchCenter.getY() + y, searchCenter.getZ() + z);
                    if (client.level.getBlockState(mutable).is(Blocks.CRAFTING_TABLE)) {
                        double distSq = mutable.distSqr(searchCenter);
                        if (distSq < closestDistSq) {
                            closestDistSq = distSq;
                            closest = mutable.immutable();
                        }
                    }
                }
            }
        }
        return closest;
    }
}
