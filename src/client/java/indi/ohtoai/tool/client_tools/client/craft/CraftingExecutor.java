package indi.ohtoai.tool.client_tools.client.craft;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

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
        OPEN_INPUT, WAIT_INPUT_OPEN, TAKE_INPUT, WAIT_INPUT_TAKE,
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
    private static final int MAX_CYCLES = 100; // safety limit

    // Precise extraction (for partial stacks)
    private int preciseExtractNeeded = 0;    // remaining single items to extract from current slot
    private int preciseExtractSlot = -1;     // shulker slot being precisely extracted

    // Targeted deposit (pre-scanned menu slots, populated after container opens)
    private final List<Integer> productDepositSlots = new ArrayList<>(); // menu slot indices (27-62) holding product
    private int productDepositIdx = 0;       // current index into productDepositSlots
    private int inventoryBeforeDeposit = 0;  // snapshot taken before opening output box

    private static final int WAIT_TICKS = 2;       // reduced from 4; safe for both local and remote servers
    private static final int CLOSE_WAIT_TICKS = 1;  // minimal wait for close packets

    private CraftingExecutor() {}

    public static CraftingExecutor getInstance() {
        if (instance == null) {
            instance = new CraftingExecutor();
        }
        return instance;
    }

    /**
     * Start executing a crafting chain.
     */
    public void start(List<RecipeChainAnalyzer.RecipeStep> steps, BlockPos stationPos,
                      BlockPos inputPos, BlockPos outputPos, Item finalProductItem,
                      int targetCount) {
        this.steps = steps;
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
        this.preciseExtractNeeded = 0;
        this.preciseExtractSlot = -1;
        this.productDepositSlots.clear();
        this.productDepositIdx = 0;
        this.inventoryBeforeDeposit = 0;
        this.errorMessage = null;

        calculateMaterialPlan();
        this.state = State.OPEN_INPUT;
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
            default -> {}
        }
    }

    public boolean isRunning() {
        return state != State.IDLE && state != State.DONE && state != State.ERROR;
    }

    public String getErrorMessage() {
        return errorMessage;
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
        if (getContainerSize(client.player.containerMenu) >= 0) {
            if (sourceItemsToTake == 0) {
                // Nothing needed this cycle — close immediately
                state = State.CLOSE_INPUT;
            } else {
                state = State.TAKE_INPUT;
                slotIndex = 0;
            }
            tickCounter = 0;
            return;
        }
        if (tickCounter > 20) error("Timed out waiting for input container to open");
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
        int containerSize = getContainerSize(menu);
        if (containerSize < 0) {
            error("Input container not open");
            return;
        }

        Item sourceItem = steps.get(0).fromItem();

        if (slotIndex < containerSize) {
            // Check if we've already taken enough (finite mode only)
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
                    // Partial stack: need fewer items than this slot holds
                    // Use precise single-item extraction
                    preciseExtractNeeded = need;
                    preciseExtractSlot = slotIndex;
                    state = State.TAKE_INPUT_PRECISE;
                    tickCounter = 0;
                } else {
                    // Take the entire stack
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
            // Scanned all container slots
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
        if (getContainerSize(menu) < 0) {
            error("Input container not open");
            return;
        }

        if (preciseExtractNeeded <= 0) {
            // Safety: should not happen
            state = State.TAKE_INPUT;
            tickCounter = 0;
            return;
        }

        quickMoveSingle(client, menu.containerId, preciseExtractSlot);
        cycleSourceTaken++;
        preciseExtractNeeded--;
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
        if (tickCounter > 20) error("Timed out waiting for crafting table to open");
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
            // Out of materials for this step
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

        // Belt-and-suspenders: check target before placing
        if (stepOutputTargets != null
            && currentStep >= 0 && currentStep < stepOutputTargets.length) {
            if (currentStepProduced >= stepOutputTargets[currentStep]) {
                state = State.NEXT_STEP;
                tickCounter = 0;
                return;
            }
        }

        RecipeChainAnalyzer.RecipeStep step = steps.get(currentStep);
        var optHolder = client.level.getRecipeManager().byKey(step.recipeId());
        if (optHolder.isEmpty()) {
            error("Recipe not found: " + step.recipeId());
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
            RecipeChainAnalyzer.RecipeStep step = steps.get(currentStep);

            // Track per-step output count
            if (stepOutputTargets != null) {
                currentStepProduced += step.toCount();
            }
            // Track cumulative final products
            if (isLastStep()) {
                finalProductsMade += step.toCount();
            }

            state = State.CHECK_STEP_MATERIALS;
            tickCounter = 0;
        }
    }

    private void doNextStep(Minecraft client) {
        currentStepProduced = 0;
        currentStep++;
        if (currentStep >= steps.size()) {
            state = State.CLOSE_TABLE;
        } else {
            state = State.CHECK_STEP_MATERIALS;
        }
        tickCounter = 0;
    }

    private boolean isLastStep() {
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
        int containerSize = getContainerSize(menu);
        if (containerSize >= 0) {
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
        if (tickCounter > 20) error("Timed out waiting for output container to open");
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
        if (getContainerSize(menu) < 0) {
            error("Output container not open");
            return;
        }

        if (productDepositIdx < productDepositSlots.size()) {
            int menuSlot = productDepositSlots.get(productDepositIdx); // direct menu slot index
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
     * Retry up to 3 times within a single output session, then give up.
     * If no more final products, proceed to CHECK_CYCLE.
     */
    private void doRetryOutput(Minecraft client) {
        if (!checkPlayer(client)) return;

        if (playerHasFinalProduct(client)) {
            outputBoxAttempts++; // count retries, NOT initial opens
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
     * After a complete input→craft→output cycle, decides whether to:
     * - Loop back to input (target not yet reached, source was available)
     * - Stop (target reached, or input exhausted, or safety limit hit)
     */
    private void doCheckCycle(Minecraft client) {
        if (!checkPlayer(client)) return;

        cycleCount++;

        // Safety limit
        if (cycleCount >= MAX_CYCLES) {
            client.player.displayClientMessage(
                Component.translatable("client-tools.ccraft.cycle_limit", MAX_CYCLES), false);
            sendDoneMessage(client);
            state = State.DONE;
            return;
        }

        if (targetCount <= 0) {
            // Infinite mode: loop while source items were found
            if (cycleSourceTaken > 0) {
                outputBoxAttempts = 0; // reset retry counter for new cycle
                state = State.OPEN_INPUT;
                tickCounter = 0;
            } else {
                sendDoneMessage(client);
                state = State.DONE;
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
            if (cycleSourceTaken > 0 || sourceItemsToTake == 0) {
                // We either took source items this cycle, or we skipped taking
                // because we already had enough in inventory. Recalc and loop.
                recalcStepTargets();
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
        int stateId = getContainerStateId(client.player.containerMenu);
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
        int stateId = getContainerStateId(client.player.containerMenu);
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

    /**
     * Returns the number of container-specific slots for any storage container
     * (chest, shulker box, barrel, hopper, dispenser, etc.), or -1 if the menu
     * is not a recognized storage container.
     *
     * All vanilla storage containers follow the same layout: the container's own
     * slots come first (indices 0..N-1), followed by exactly 36 player inventory
     * slots. So {@code containerSize = totalSlots - 36}.
     */
    private static int getContainerSize(AbstractContainerMenu menu) {
        if (menu == null) return -1;
        if (menu instanceof CraftingMenu) return -1; // crafting table is handled separately
        int total = menu.slots.size();
        if (total <= 36) return -1; // no container-specific slots
        return total - 36;
    }

    private static int getContainerStateId(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        try {
            java.lang.reflect.Field field = menu.getClass().getDeclaredField("stateId");
            field.setAccessible(true);
            return field.getInt(menu);
        } catch (Exception e) {
            return 0;
        }
    }

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
