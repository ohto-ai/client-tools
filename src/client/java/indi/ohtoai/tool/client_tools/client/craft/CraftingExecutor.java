package indi.ohtoai.tool.client_tools.client.craft;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
 * emptying it.
 *
 * Full cycle (repeats until target reached or input exhausted):
 *   OPEN_INPUT  → take calculated source items (finite) or all (infinite) → close
 *   OPEN_TABLE  → per-step inner loop (until step target or no materials) → close
 *   OPEN_OUTPUT → deposit all final products → close
 *   CHECK_CYCLE → target reached? → DONE
 *               → source taken?     → loop back to OPEN_INPUT
 *               → nothing taken?    → DONE (input exhausted)
 */
public class CraftingExecutor {

    private enum State {
        IDLE,
        // Input box
        OPEN_INPUT, WAIT_INPUT_OPEN, TAKE_INPUT, WAIT_INPUT_TAKE, CLOSE_INPUT, WAIT_INPUT_CLOSE,
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

    private static final int SHULKER_SLOTS = 27;
    private static final int PLAYER_SLOTS_IN_SHULKER = 36;
    private static final int WAIT_TICKS = 4;

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
            case CLOSE_INPUT -> doCloseContainer(client, State.WAIT_INPUT_CLOSE);
            case WAIT_INPUT_CLOSE -> doWaitClose(client, State.OPEN_TABLE);
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
            case WAIT_CLOSE_TABLE -> doWaitClose(client, State.OPEN_OUTPUT);
            // Output box
            case OPEN_OUTPUT -> doOpenOutput(client);
            case WAIT_OUTPUT_OPEN -> doWaitOutputOpen(client);
            case DEPOSIT_OUTPUT -> doDepositOutput(client);
            case WAIT_OUTPUT_DEPOSIT -> doWaitOutputDeposit(client);
            case CLOSE_OUTPUT -> doCloseContainer(client, State.WAIT_OUTPUT_CLOSE);
            case WAIT_OUTPUT_CLOSE -> doWaitClose(client, State.RETRY_OUTPUT);
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
        if (client.player.containerMenu instanceof ShulkerBoxMenu) {
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
        if (tickCounter > 20) error("Timed out waiting for input shulker box to open");
    }

    /**
     * Takes source items from the input box, stopping when enough have been
     * taken (finite mode) or all slots have been scanned (infinite mode).
     * Tracks how many were taken this cycle (cycleSourceTaken).
     */
    private void doTakeInput(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (!(client.player.containerMenu instanceof ShulkerBoxMenu menu)) {
            error("Input shulker box not open");
            return;
        }

        Item sourceItem = steps.get(0).fromItem();

        if (slotIndex < SHULKER_SLOTS) {
            // Check if we've already taken enough (finite mode only)
            if (sourceItemsToTake > 0 && cycleSourceTaken >= sourceItemsToTake) {
                state = State.CLOSE_INPUT;
                tickCounter = 0;
                return;
            }

            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (stack.getItem() == sourceItem) {
                quickMove(client, menu.containerId, slotIndex);
                cycleSourceTaken += stack.getCount();
            }
            slotIndex++;
            tickCounter = 0;
            state = State.WAIT_INPUT_TAKE;
        } else {
            // Scanned all shulker slots
            state = State.CLOSE_INPUT;
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

    private void doOpenOutput(Minecraft client) {
        if (!checkPlayer(client)) return;
        outputBoxAttempts++;
        openBlock(client, outputPos);
        state = State.WAIT_OUTPUT_OPEN;
        tickCounter = 0;
    }

    private void doWaitOutputOpen(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (client.player.containerMenu instanceof ShulkerBoxMenu) {
            state = State.DEPOSIT_OUTPUT;
            tickCounter = 0;
            slotIndex = SHULKER_SLOTS;
            return;
        }
        if (tickCounter > 20) error("Timed out waiting for output shulker box to open");
    }

    private void doDepositOutput(Minecraft client) {
        if (!checkPlayer(client)) return;
        if (!(client.player.containerMenu instanceof ShulkerBoxMenu menu)) {
            error("Output shulker box not open");
            return;
        }
        int totalSlots = SHULKER_SLOTS + PLAYER_SLOTS_IN_SHULKER;
        if (slotIndex < totalSlots) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (stack.getItem() == finalProductItem) {
                quickMove(client, menu.containerId, slotIndex);
                totalCrafted += stack.getCount();
            }
            slotIndex++;
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
     * Retry up to 3 times, then give up.
     * If no more final products, proceed to CHECK_CYCLE.
     */
    private void doRetryOutput(Minecraft client) {
        if (!checkPlayer(client)) return;

        if (playerHasFinalProduct(client)) {
            if (outputBoxAttempts >= 3) {
                client.player.displayClientMessage(
                    Component.literal("§c/ccraft: Output box appears full after §e" + outputBoxAttempts
                        + "§c attempts. Stopping."), false);
                state = State.DONE;
                return;
            }
            // Retry opening output box
            state = State.OPEN_OUTPUT;
            tickCounter = 0;
        } else {
            // Successfully deposited everything — check if we need more cycles
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
                Component.literal("§c/ccraft: Reached cycle limit (§e" + MAX_CYCLES + "§c). Stopping."), false);
            sendDoneMessage(client);
            state = State.DONE;
            return;
        }

        if (targetCount <= 0) {
            // Infinite mode: loop while source items were found
            if (cycleSourceTaken > 0) {
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
                state = State.OPEN_INPUT;
                tickCounter = 0;
            } else {
                // Input box is truly empty — we tried to take but got nothing
                client.player.displayClientMessage(
                    Component.literal("§e/ccraft: Input box exhausted. Made §e"
                        + finalProductsMade + "§e/§e" + targetCount + "§e products."), false);
                sendDoneMessage(client);
                state = State.DONE;
            }
        }
    }

    private void sendDoneMessage(Minecraft client) {
        if (client.player != null) {
            String msg;
            if (totalCrafted > 0) {
                msg = "§aCrafting complete! Deposited §e" + totalCrafted + "§a item(s).";
            } else if (finalProductsMade > 0) {
                msg = "§aCrafting complete! Made §e" + finalProductsMade + "§a final product(s).";
            } else {
                msg = "§aCrafting complete! Already have enough.";
            }
            client.player.displayClientMessage(Component.literal(msg), false);
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

    private void doWaitClose(Minecraft client, State nextState) {
        if (tickCounter >= WAIT_TICKS) {
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

    private void quickMove(Minecraft client, int containerId, int slot) {
        int stateId = getContainerStateId(client.player.containerMenu);
        client.player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                containerId, stateId, slot, 0,
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
            client.player.displayClientMessage(Component.literal("§c/ccraft error: " + msg), false);
        }
    }

    // ==================== Static utilities ====================

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
