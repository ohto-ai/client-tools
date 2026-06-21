package indi.ohtoai.tool.client_tools.client.util;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared container-related utilities used by multiple executors.
 */
public final class ContainerUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger("client-tools");

    private ContainerUtils() {}

    /**
     * Returns the number of container-specific slots for any storage container
     * (chest, shulker box, barrel, hopper, dispenser, etc.), or -1 if the menu
     * is not a recognized storage container.
     *
     * <p>All vanilla storage containers follow the same layout: the container's own
     * slots come first (indices 0..N-1), followed by exactly 36 player inventory
     * slots. So {@code containerSize = totalSlots - 36}.
     */
    public static int getContainerSize(AbstractContainerMenu menu) {
        if (menu == null) return -1;
        if (menu instanceof CraftingMenu) return -1; // crafting table is handled separately
        int total = menu.slots.size();
        if (total <= 36) return -1; // no container-specific slots
        return total - 36;
    }

    /**
     * Reads the container's {@code stateId} via reflection.
     * The stateId is used in {@code ServerboundContainerClickPacket} to keep
     * the server and client in sync. A wrong value causes the server to
     * silently reject clicks.
     *
     * @return the stateId, or 0 if reflection fails
     */
    public static int getContainerStateId(AbstractContainerMenu menu) {
        try {
            java.lang.reflect.Field field = menu.getClass().getDeclaredField("stateId");
            field.setAccessible(true);
            return field.getInt(menu);
        } catch (Exception e) {
            LOGGER.warn("Failed to read container stateId via reflection — clicks may be rejected by server", e);
            return 0;
        }
    }
}
