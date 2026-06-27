package indi.ohtoai.tool.client_tools.client.mixin;

import indi.ohtoai.tool.client_tools.client.riptide.RiptideState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides {@link Entity#isInWaterOrRain()} to always return {@code true}
 * for the local player when the Riptide override toggle is enabled.
 *
 * <p>This allows Riptide tridents to trigger their spin attack and launch
 * anywhere, regardless of biome, weather, or sky access. The vanilla
 * {@code TridentItem.releaseUsing()} method runs on both client and server;
 * on the client side it applies {@code player.push()} and
 * {@code player.startAutoSpinAttack()} once the water check passes.
 *
 * <p>In singleplayer, the integrated server shares the same Entity class,
 * so the server-side check also passes for a complete vanilla Riptide
 * experience.
 */
@Mixin(Entity.class)
public class RiptideMixin {

    @Inject(method = "isInWaterOrRain", at = @At("RETURN"), cancellable = true)
    private void overrideIsInWaterOrRain(CallbackInfoReturnable<Boolean> cir) {
        if (RiptideState.isEnabled() && (Object) this == Minecraft.getInstance().player) {
            cir.setReturnValue(true);
        }
    }
}
