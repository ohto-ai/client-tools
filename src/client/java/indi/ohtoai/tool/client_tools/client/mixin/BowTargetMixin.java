package indi.ohtoai.tool.client_tools.client.mixin;

import indi.ohtoai.tool.client_tools.client.bow.BowTargetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses mouse-driven camera rotation when bow auto-aim
 * targeting is active.
 *
 * <p>When {@link BowTargetManager#isActive()} returns {@code true},
 * the player's yaw/pitch are controlled by the tick handler —
 * mouse input must not override them. This mixin cancels
 * {@link Entity#turn(double, double)} for the local player
 * during targeting.
 */
@Mixin(Entity.class)
public class BowTargetMixin {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void onTurn(double yRot, double xRot, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player
            && BowTargetManager.getInstance().isActive()) {
            ci.cancel();
        }
    }
}
