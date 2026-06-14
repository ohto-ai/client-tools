package indi.ohtoai.tool.client_tools.client.mixin;

import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses only container GUIs (crafting table, shulker box, inventory, etc.)
 * while the crafting executor is running. Non-container screens like the pause
 * menu, chat, and death screen are allowed through so the user can still
 * interact with the game normally during execution.
 */
@Mixin(Minecraft.class)
public class SuppressScreenMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (CraftingExecutor.getInstance().isRunning() && screen != null) {
            // Only block container screens — allow pause menu, chat, etc.
            if (screen instanceof AbstractContainerScreen) {
                ci.cancel();
            }
        }
    }
}
