package indi.ohtoai.tool.client_tools.client.mixin;

import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses container GUIs (crafting table, shulker box, inventory, etc.)
 * while the crafting executor is running, so the player can chat or use other
 * screens without interruption. Uses a belt-and-suspenders approach:
 * <ol>
 *   <li>{@code HEAD} cancel — stops {@code setScreen(container)} before it runs.</li>
 *   <li>{@code RETURN} fallback — if a container screen leaked through
 *       (e.g. Fabric API redirect, direct field write), immediately restore
 *       the previous user-facing screen.</li>
 * </ol>
 */
@Mixin(Minecraft.class)
public class SuppressScreenMixin {

    @Unique
    private Screen client_tools$previousScreen;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreenHead(Screen screen, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        client_tools$previousScreen = self.screen;

        if (CraftingExecutor.getInstance().isRunning() && screen instanceof AbstractContainerScreen) {
            ci.cancel();
        }
    }

    @Inject(method = "setScreen", at = @At("RETURN"))
    private void onSetScreenReturn(Screen screen, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;

        // Fallback: if a container screen leaked through despite the HEAD cancel
        // (e.g. a Fabric API redirect or direct field write), restore the
        // previous screen that the player was actually using.
        if (CraftingExecutor.getInstance().isRunning()
            && self.screen instanceof AbstractContainerScreen
            && client_tools$previousScreen != null
            && !(client_tools$previousScreen instanceof AbstractContainerScreen)) {

            // Call setScreen recursively to properly run the screen lifecycle.
            // The previous screen is a ChatScreen / PauseScreen / etc., so it
            // will pass the HEAD check above and open normally.
            self.setScreen(client_tools$previousScreen);
        }
    }
}
