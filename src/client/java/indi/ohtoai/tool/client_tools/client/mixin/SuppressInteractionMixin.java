package indi.ohtoai.tool.client_tools.client.mixin;

import indi.ohtoai.tool.client_tools.client.craft.CraftingExecutor;
import indi.ohtoai.tool.client_tools.client.shop.ShopExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks the player from interacting with blocks/entities while the
 * crafting executor is running. This prevents the user from accidentally
 * opening other containers (which would corrupt the container state).
 */
@Mixin(MultiPlayerGameMode.class)
public class SuppressInteractionMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult result,
                             CallbackInfoReturnable<InteractionResult> cir) {
        if (CraftingExecutor.getInstance().isRunning() || ShopExecutor.getInstance().isRunning()) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void onUseItem(Player player, InteractionHand hand,
                           CallbackInfoReturnable<InteractionResult> cir) {
        if (CraftingExecutor.getInstance().isRunning() || ShopExecutor.getInstance().isRunning()) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
